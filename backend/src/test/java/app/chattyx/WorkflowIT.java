package app.chattyx;

import app.chattyx.automation.AutomationWorker;
import app.chattyx.connections.*;
import app.chattyx.conversations.ConversationService;
import app.chattyx.delivery.DeliveryService;
import app.chattyx.generation.ReplyModel;
import app.chattyx.memory.MemoryService;
import app.chattyx.operations.BudgetService;
import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {
    "chatty.mode=demo", "chatty.workers-enabled=false",
    "chatty.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "chatty.bootstrap-token=synthetic-bootstrap-token-for-tests-only"
})
@Import(WorkflowIT.TimeConfiguration.class)
class WorkflowIT {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T12:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Autowired Db db;
    @Autowired ConversationService chats;
    @Autowired ConnectionService connections;
    @Autowired SettingsService settings;
    @Autowired DeliveryService stoppedDelivery;
    @Autowired ChannelRegistry channels;
    @Autowired Events events;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    DeliveryService delivery;
    @Autowired AutomationWorker worker;
    @Autowired MemoryService memory;
    @Autowired BudgetService budget;
    @Autowired TestClock clock;
    @MockitoBean ReplyModel model;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean FakeMessagingAdapter fake;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    UUID connection;
    UUID chat;

    @BeforeEach void setup() {
        // Production scheduling stays disabled; each test advances the real service explicitly.
        delivery = new DeliveryService(db, chats, settings, channels, clock, events, budget, transactions, true);
        db.jdbc.execute("TRUNCATE connection, usage_entry, audit_event CASCADE");
        db.jdbc.update("DELETE FROM app_settings WHERE scope<>'global'");
        db.jdbc.update("UPDATE app_settings SET body=jsonb_set(jsonb_set(body,'{enabled}','true'),'{monthlyBudgetUsd}','30') WHERE scope='global'");
        connection = connections.create("Synthetic account", "fake");
        connections.authorize(connection, "test");
        connections.enable(connection, true);
        chat = db.jdbc.queryForObject("SELECT id FROM conversation WHERE connection_id=? AND external_id='1001'", UUID.class, connection);
        db.jdbc.update("UPDATE conversation SET selected=true,imported=true,mode='AUTO' WHERE id=?", chat);
        when(model.generate(any())).thenReturn(new ReplyModel.Reply("reply", "О, здорово 🙂", "CONTEXT_REPLY"));
    }
    MessagingAdapter.Incoming incoming(String id, String text) {
        return new MessagingAdapter.Incoming(connection,"1001",id,text,clock.instant(),false,false,false,null,null,null);
    }
    void receive(String id) { chats.receive(incoming(id,"Реплика " + id)); }
    UUID preparedReply() {
        receive(UUID.randomUUID().toString()); clock.advance(8); worker.runOne(chat);
        return db.jdbc.queryForObject("SELECT id FROM outbound WHERE conversation_id=? AND status='READY'", UUID.class, chat);
    }
    String status(UUID id) { return db.jdbc.queryForObject("SELECT status FROM outbound WHERE id=?",String.class,id); }

    @Test void disabledProcessingRejectsNewManualReplyWithoutChangingConversation() {
        var before=chats.get(chat);
        assertThatThrownBy(()->stoppedDelivery.manual(chat,"Synthetic reply","disabled-send"))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PROCESSING_DISABLED"));
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM outbound",Integer.class)).isZero();
        assertThat(chats.get(chat)).containsEntry("mode",before.get("mode")).containsEntry("version",before.get("version"));
        verify(fake,never()).sendText(any());
    }
    @Test void existingManualRequestRemainsIdempotentWhenProcessingStops() {
        var accepted=delivery.manual(chat,"Synthetic reply","already-accepted");
        assertThat(stoppedDelivery.manual(chat,"Synthetic reply","already-accepted").get("id")).isEqualTo(accepted.get("id"));
        stoppedDelivery.tick();
        assertThat(status(UUID.fromString(accepted.get("id").toString()))).isEqualTo("READY");
        verify(fake,never()).sendText(any());
    }
    @Test void scheduledDeliverySendsOwnerReplyWithoutAutomationOrModelKey() {
        db.jdbc.update("UPDATE app_settings SET body=jsonb_set(body,'{enabled}','false') WHERE scope='global'");
        db.jdbc.update("UPDATE connection SET enabled=false WHERE id=?",connection);
        db.jdbc.update("DELETE FROM credential WHERE name='openai'");
        var accepted=delivery.manual(chat,"Synthetic manual reply","manual-without-ai");
        delivery.tick();delivery.tick();
        assertThat(status(UUID.fromString(accepted.get("id").toString()))).isEqualTo("SENT");
        assertThat(chats.get(chat)).containsEntry("mode","PAUSED");
        verify(fake,times(1)).sendText(any());verifyNoInteractions(model);
    }
    @Test void manualReplyToDisconnectedAccountIsNotQueued() {
        db.jdbc.update("UPDATE connection SET status='DISCONNECTED' WHERE id=?",connection);
        assertThatThrownBy(()->delivery.manual(chat,"Synthetic reply","offline-send"))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("TELEGRAM_NOT_CONNECTED"));
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM outbound",Integer.class)).isZero();
        verify(fake,never()).sendText(any());
    }
    @Test void runtimeEndpointExposesOnlyAvailabilityAndRequiresAuthentication() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/runtime"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        db.jdbc.update("DELETE FROM credential WHERE name='openai'");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/runtime")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("owner")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("{\"workersEnabled\":false,\"automationEnabled\":true,\"modelConfigured\":false,\"demo\":true}"));
    }

    @Test void fiveMessagesProduceOneReplyContainingWholeBurst() {
        for (int n=0;n<5;n++) { receive("burst-"+n); clock.advance(3); }
        worker.runOne(chat);
        verifyNoInteractions(model);
        clock.advance(5); worker.runOne(chat);
        var context = org.mockito.ArgumentCaptor.forClass(ReplyModel.Context.class);
        verify(model).generate(context.capture());
        assertThat(context.getValue().messages()).hasSize(5);
        UUID out = db.jdbc.queryForObject("SELECT id FROM outbound", UUID.class);
        clock.advance(6); delivery.send(out,chat); delivery.send(out,chat);
        assertThat(status(out)).isEqualTo("SENT");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM message WHERE direction='OUT'",Integer.class)).isEqualTo(1);
        assertThat(chats.get(chat).get("mode")).isEqualTo("AUTO");
    }
    @Test void duplicateIncomingDoesNotChangeVersionOrProduceDuplicate() {
        var message=incoming("duplicate","Привет");chats.receive(message);
        Object version=chats.get(chat).get("version");chats.receive(message);
        assertThat(chats.get(chat).get("version")).isEqualTo(version);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM message",Integer.class)).isEqualTo(1);
    }
    @Test void newIncomingDuringGenerationDiscardsOldResult() {
        receive("first");clock.advance(8);
        when(model.generate(any())).thenAnswer(invocation->{receive("new");return new ReplyModel.Reply("reply","Устарело","TEST");});
        worker.runOne(chat);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM outbound",Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT state FROM automation_job WHERE conversation_id=?",String.class,chat)).isEqualTo("WAITING");
    }
    @Test void manualSendCancelsPreparedAutomationAndIsIdempotent() {
        UUID auto=preparedReply();
        var manual=delivery.manual(chat,"Отвечу сам","manual-key");
        var repeated=delivery.manual(chat,"Отвечу сам","manual-key");
        assertThat(repeated.get("id")).isEqualTo(manual.get("id"));
        delivery.send(auto,chat);
        assertThat(status(auto)).isEqualTo("CANCELLED");
        assertThat(chats.get(chat).get("mode")).isEqualTo("PAUSED");
        assertThatThrownBy(()->delivery.manual(chat,"Другой текст","manual-key")).isInstanceOf(ApiException.class);
    }
    @Test void manualMessageFromAnotherClientPausesUntilResume() {
        UUID auto=preparedReply();
        chats.receive(new MessagingAdapter.Incoming(connection,"1001","owner-external","Сам отвечу",clock.instant(),true,false,false,null,null,null));
        receive("new-input");
        assertThat(status(auto)).isEqualTo("CANCELLED");
        assertThat(chats.get(chat).get("mode")).isEqualTo("PAUSED");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM automation_job",Integer.class)).isZero();
        chats.control(chat,"resume");
        assertThat(chats.get(chat).get("mode")).isEqualTo("AUTO");
    }
    @Test void restartNeverRetriesAmbiguousSend() {
        UUID out=preparedReply();
        db.jdbc.update("UPDATE outbound SET status='SENDING' WHERE id=?",out);
        delivery.recover(); delivery.send(out,chat);
        assertThat(status(out)).isEqualTo("UNKNOWN");
        assertThat(chats.get(chat).get("mode")).isEqualTo("PAUSED");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM message WHERE direction='OUT'",Integer.class)).isZero();
    }
    @Test void editAndDeleteInvalidatePreparedReply() {
        UUID out=preparedReply();String external=db.jdbc.queryForObject("SELECT external_id FROM message WHERE direction='IN'",String.class);
        chats.edit(connection,"1001",external,"Исправлено");
        assertThat(status(out)).isEqualTo("CANCELLED");
        chats.delete(connection,"1001",List.of(external));
        assertThat(chats.messages(chat,null,"")).isEmpty();
    }
    @Test void historicalMessagesNeverTriggerReply() {
        chats.receive(new MessagingAdapter.Incoming(connection,"1001","old","Старый вопрос",clock.instant().minusSeconds(10000),false,true,false,null,null,null));
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM automation_job",Integer.class)).isZero();
        assertThat(chats.get(chat).get("mode")).isEqualTo("AUTO");
    }
    @Test void forgettingRemovesSourceFromReplyContextAndKeepsChatsIsolated() {
        receive("fact-source");UUID mid=db.jdbc.queryForObject("SELECT id FROM message",UUID.class);UUID fact=UUID.randomUUID();
        db.jdbc.update("INSERT INTO memory_fact(id,conversation_id,subject,fact_key,content) VALUES (?,?,'contact','city','Варшава')",fact,chat);
        db.jdbc.update("INSERT INTO fact_source VALUES (?,?)",fact,mid);
        UUID other=db.jdbc.queryForObject("SELECT id FROM conversation WHERE external_id='1002'",UUID.class);
        assertThat(memory.list(other)).isEmpty();
        memory.forget(chat,fact,((Number)chats.get(chat).get("version")).longValue());
        assertThat(memory.list(chat)).isEmpty();assertThat(worker.contextMessages(chat)).isEmpty();
    }
    @Test void budgetReservationsAreAtomicAcrossConcurrentRequests() throws Exception {
        db.jdbc.update("UPDATE app_settings SET body=jsonb_set(body,'{monthlyBudgetUsd}','1') WHERE scope='global'");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Callable<Boolean>>();
            for(int n=0;n<10;n++)tasks.add(()->{try{budget.reserve(chat,"reply","test","v1",new BigDecimal("0.30"));return true;}catch(ApiException e){return false;}});
            long accepted=0;for(var result:pool.invokeAll(tasks))if(result.get())accepted++;
            assertThat(accepted).isEqualTo(3);assertThat(budget.used()).isEqualByComparingTo("0.90");
        }
    }
    @Test void globalStopCancelsAutomationButPreservesManualMessage() {
        UUID auto=preparedReply();UUID manual=UUID.fromString(delivery.manual(chat,"Ручной ответ","owner-after-stop").get("id").toString());
        var body=Json.MAPPER.convertValue(settings.read("global"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        body.put("enabled",false);settings.save("global",((Number)settings.view("global").get("version")).longValue(),body);
        assertThat(status(auto)).isEqualTo("CANCELLED");
        assertThat(status(manual)).isEqualTo("READY");
        delivery.send(manual,chat);assertThat(status(manual)).isEqualTo("SENT");
    }

    @Test void confirmationArrivingBeforeSendReceiptIsNotLost() {
        UUID out=preparedReply();clock.advance(6);
        doAnswer(invocation->{
            delivery.confirmation(new MessagingAdapter.Delivered(connection,"1001","temporary-early","final-early",true,"CONFIRMED"));
            return new MessagingAdapter.Receipt("temporary-early",null,false);
        }).when(fake).sendText(any());
        delivery.send(out,chat);
        assertThat(status(out)).isEqualTo("SENT");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM delivery_receipt",Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT external_id FROM message WHERE direction='OUT'",String.class)).isEqualTo("final-early");
    }
    @Test void ambiguousAdapterFailureIsNeverRetried() {
        UUID out=preparedReply();clock.advance(6);
        doThrow(new ApiException(504,"TELEGRAM_RESULT_UNKNOWN")).when(fake).sendText(any());
        delivery.send(out,chat);delivery.send(out,chat);
        assertThat(status(out)).isEqualTo("UNKNOWN");verify(fake,times(1)).sendText(any());
    }
    @Test void floodWaitRetriesOnlyAfterServerDeadline() {
        UUID out=preparedReply();clock.advance(6);
        doThrow(new RateLimited(20)).doReturn(new MessagingAdapter.Receipt("after-wait","after-wait",true)).when(fake).sendText(any());
        delivery.send(out,chat);assertThat(status(out)).isEqualTo("READY");
        delivery.send(out,chat);verify(fake,times(1)).sendText(any());
        clock.advance(20);delivery.send(out,chat);assertThat(status(out)).isEqualTo("SENT");
    }
    @Test void missingMediaHandsConversationToOwnerWithoutInventedReply() {
        chats.receive(new MessagingAdapter.Incoming(connection,"1001","large-voice","",clock.instant(),false,false,false,null,new MessagingAdapter.Media("test","VOICE","audio/ogg",1000,601),null));
        clock.advance(8);worker.runOne(chat);
        assertThat(chats.get(chat).get("mode")).isEqualTo("PAUSED");
        assertThat(chats.get(chat).get("needsAttention")).isEqualTo("MEDIA_LIMIT");
        verifyNoInteractions(model);
        delivery.manual(chat,"Не удалось послушать, отвечу позже","after-media-error");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM outbound WHERE source='WEB_OWNER'",Integer.class)).isEqualTo(1);
    }
    @Test void paginationDoesNotRepeatRowsWhenNewMessagesArrive() {
        for(int n=0;n<60;n++){receive("history-"+n);clock.advance(1);}
        var first=chats.messages(chat,null,"");var last=first.getLast();
        String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("sentAt")+"|"+last.get("id")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        receive("newer");var next=chats.messages(chat,cursor,"");
        assertThat(next).hasSize(10);
        assertThat(next.stream().map(row->row.get("id")).toList()).doesNotContainAnyElementsOf(first.stream().map(row->row.get("id")).toList());
    }
    @Test void tenConversationsCanGenerateIndependently() throws Exception {
        var ids=new ArrayList<UUID>();
        for(int n=0;n<10;n++){
            UUID id=UUID.randomUUID();String external="parallel-"+n;
            db.jdbc.update("INSERT INTO conversation(id,connection_id,external_id,title,selected,imported,mode) VALUES (?,?,?,'Synthetic',true,true,'AUTO')",id,connection,external);
            chats.receive(new MessagingAdapter.Incoming(connection,external,"input","Привет",clock.instant(),false,false,false,null,null,null));ids.add(id);
        }
        clock.advance(8);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var tasks=ids.stream().<Callable<Void>>map(id->()->{worker.runOne(id);return null;}).toList();
            for(var result:pool.invokeAll(tasks))result.get();
        }
        assertThat(db.jdbc.queryForObject("SELECT count(DISTINCT conversation_id) FROM outbound WHERE status='READY'",Integer.class)).isEqualTo(10);
    }
    @Test void privateApisAndFilesRequireSessionAndMutationsRequireCsrf() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/conversations")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/attachments/"+UUID.randomUUID())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/events")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/stop")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
}
