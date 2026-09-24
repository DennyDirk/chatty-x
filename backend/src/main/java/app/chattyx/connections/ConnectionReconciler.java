package app.chattyx.connections;

import app.chattyx.conversations.ConversationService;
import app.chattyx.shared.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConnectionReconciler {
  private final Db db;
  private final ChannelRegistry channels;
  private final ConversationService chats;
  private final Clock clock;
  private final boolean enabled;
  private final AtomicBoolean busy=new AtomicBoolean();
  public ConnectionReconciler(Db db,ChannelRegistry channels,ConversationService chats,Clock clock,@Value("${chatty.workers-enabled}") boolean enabled){this.db=db;this.channels=channels;this.chats=chats;this.clock=clock;this.enabled=enabled;}

  @Scheduled(fixedDelay=5000,initialDelay=15000)
  public void tick(){
    if(!enabled||!busy.compareAndSet(false,true))return;
    Thread.ofVirtual().name("history-reconciliation").start(()->{
      try {
        for(var chat:db.list("SELECT c.* FROM conversation c JOIN connection n ON n.id=c.connection_id WHERE c.selected AND c.imported AND n.status='READY' AND n.adapter='telegram' AND c.next_sync_at<=? ORDER BY c.next_sync_at LIMIT 1",Timestamp.from(clock.instant())))sync(UUID.fromString(chat.get("id").toString()));
      }finally{busy.set(false);}
    });
  }

  public void sync(UUID id){
    var chat=chats.get(id);UUID connection=UUID.fromString(chat.get("connectionId").toString());String external=chat.get("externalId").toString();
    if(!Boolean.TRUE.equals(chat.get("selected"))||!Boolean.TRUE.equals(chat.get("imported")))return;
    var adapter=channels.forConnection(connection);
    int retry=300;
    try {
      var known=db.list("SELECT external_id,body,sent_at FROM message WHERE conversation_id=? AND NOT deleted ORDER BY sent_at DESC LIMIT 100",id);
      Map<String,String> bodies=new HashMap<>();known.forEach(row->bodies.put(row.get("externalId").toString(),row.get("body").toString()));
      Instant latest=known.isEmpty()?clock.instant():Instant.parse(known.getFirst().get("sentAt").toString());
      if(!known.isEmpty())adapter.fetchMessages(connection,external,new ArrayList<>(bodies.keySet())).ifPresent(snapshot->{
        for(var message:snapshot.messages())if(!Objects.equals(bodies.get(message.messageId()),message.text()))chats.edit(connection,external,message.messageId(),message.text());
        if(!snapshot.missingIds().isEmpty())chats.delete(connection,external,snapshot.missingIds());
      });
      String cursor=null;int count=0;boolean done=false;
      while(count<1000&&!done){
        var page=adapter.fetchHistory(connection,external,cursor,Math.min(100,1000-count));
        if(page.messages().isEmpty())break;
        var chronological=new ArrayList<>(page.messages());Collections.reverse(chronological);
        for(var message:chronological){
          count++;if(message.time().isBefore(latest)){done=true;continue;}
          chats.receive(new MessagingAdapter.Incoming(connection,external,message.messageId(),message.text(),message.time(),message.outgoing(),false,message.ephemeral(),message.replyTo(),message.media(),message.sendingId()));
        }
        if(page.nextCursor()==null||page.nextCursor().equals(cursor))break;cursor=page.nextCursor();
      }
      db.jdbc.update("UPDATE conversation SET sync_error=NULL WHERE id=?",id);
    }catch(Exception error){
      retry=error instanceof RateLimited limited?limited.retryAfterSeconds():300;
      db.jdbc.update("UPDATE conversation SET sync_error=? WHERE id=?",error instanceof ApiException api?api.code():"SYNC_UNAVAILABLE",id);
    }finally{db.jdbc.update("UPDATE conversation SET next_sync_at=? WHERE id=?",Timestamp.from(clock.instant().plusSeconds(retry)),id);}
  }
}
