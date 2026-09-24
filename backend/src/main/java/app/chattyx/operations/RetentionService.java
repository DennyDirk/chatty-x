package app.chattyx.operations;

import app.chattyx.media.MediaService;
import app.chattyx.conversations.ConversationService;
import app.chattyx.personas.SettingsService;
import app.chattyx.shared.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RetentionService {

  private final Db db;
  private final MediaService media;
  private final SettingsService settings;
  private final Clock clock;
  private final ConversationService chats;
  private final TransactionTemplate tx;

  public RetentionService(
    Db db,
    MediaService media,
    SettingsService settings,
    ConversationService chats,
    Clock clock,
    org.springframework.transaction.PlatformTransactionManager tm
  ) {
    this.db = db;
    this.media = media;
    this.settings = settings;
    this.chats = chats;
    this.clock = clock;
    tx = new TransactionTemplate(tm);
  }

  @Scheduled(fixedDelay = 900000, initialDelay = 60000)
  public void cleanup() {
    for(UUID chat:db.jdbc.queryForList("SELECT id FROM conversation",UUID.class))clean(chat,false);
    var cutoff=Timestamp.from(clock.instant().minus(Duration.ofDays(settings.read("global").path("messageRetentionDays").asInt(180))));
    db.jdbc.update("DELETE FROM audit_event WHERE created_at<?",cutoff);
    db.jdbc.update("DELETE FROM delivery_receipt WHERE created_at<?",cutoff);
  }

  public void eraseConversation(UUID chat){
    tx.executeWithoutResult(s->{db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE",chat);chats.pause(chat,"LOCAL_DATA_DELETED");db.jdbc.update("UPDATE conversation SET selected=false,imported=false,import_cancelled=true WHERE id=?",chat);});
    clean(chat,true);
  }

  private void clean(UUID chat,boolean erase) {
    var cfg = settings.effective(chat);
    Timestamp fileCutoff = Timestamp.from(
        erase?clock.instant().plusSeconds(1):clock.instant().minus(Duration.ofDays(cfg.path("fileRetentionDays").asInt(30)))
      ),
      messageCutoff = Timestamp.from(
        erase?clock.instant().plusSeconds(1):clock.instant().minus(Duration.ofDays(cfg.path("messageRetentionDays").asInt(180)))
      );
    for (var a : db.list(
      "SELECT a.id,a.local_name FROM attachment a JOIN message m ON m.id=a.message_id WHERE m.conversation_id=? AND (a.local_name IS NOT NULL OR NOT a.cache_released OR a.transcript IS NOT NULL) AND (a.created_at<? OR a.status='DELETED' OR m.sent_at<? OR m.deleted)",
      chat,
      fileCutoff,
      messageCutoff
    )) {
      media.remove((String) a.get("localName"));
      db.jdbc.update(
        "UPDATE attachment SET local_name=NULL,transcript=NULL,status='EXPIRED' WHERE id=?",
        UUID.fromString(a.get("id").toString())
      );
      try{media.releaseCache(UUID.fromString(a.get("id").toString()));}catch(Exception error){db.audit("CACHE_CLEANUP_PENDING",a.get("id"));}
      db.jdbc.update("UPDATE conversation SET summary='' WHERE id=?",chat);
    }
    tx.executeWithoutResult(s -> {
      {
        db.one("SELECT id FROM conversation WHERE id=? FOR UPDATE", chat);
        db.jdbc.update(
          "DELETE FROM memory_fact WHERE conversation_id=? AND NOT pinned AND id IN (SELECT fact_id FROM fact_source s JOIN message m ON m.id=s.message_id WHERE m.sent_at<? OR m.deleted OR m.id IN (SELECT message_id FROM attachment WHERE status='EXPIRED'))",
          chat,
          messageCutoff
        );
        int removed=db.jdbc.update("UPDATE message SET body='',deleted=true,handled=true WHERE conversation_id=? AND sent_at<? AND NOT deleted",chat,messageCutoff);
        db.jdbc.update("DELETE FROM message m WHERE m.conversation_id=? AND m.deleted AND NOT EXISTS(SELECT 1 FROM attachment a WHERE a.message_id=m.id AND NOT a.cache_released)",chat);
        db.jdbc.update("DELETE FROM memory_tombstone t WHERE t.conversation_id=? AND NOT EXISTS(SELECT 1 FROM message m WHERE m.id=t.source_id)",chat);
        if(removed>0||erase){db.jdbc.update("UPDATE conversation SET summary='' WHERE id=?",chat);chats.invalidate(chat);}
        db.jdbc.update("UPDATE outbound SET body='',status=CASE WHEN status IN ('READY','DRAFT','STALE') THEN 'CANCELLED' ELSE status END WHERE conversation_id=? AND created_at<?",chat,messageCutoff);
        if(erase){db.jdbc.update("DELETE FROM memory_fact WHERE conversation_id=?",chat);db.jdbc.update("DELETE FROM memory_tombstone WHERE conversation_id=?",chat);db.jdbc.update("DELETE FROM memory_job WHERE conversation_id=?",chat);db.jdbc.update("DELETE FROM app_settings WHERE scope=?","conversation:"+chat);db.jdbc.update("UPDATE outbound SET body='' WHERE conversation_id=?",chat);}
      }
    });
  }
}
