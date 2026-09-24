package app.chattyx.generation;

import app.chattyx.shared.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ConfiguredReplyModel implements ReplyModel {
 private final OpenAiClient client;private final boolean demo;private final String prompt;
 public ConfiguredReplyModel(OpenAiClient client,@Value("${chatty.mode}")String mode)throws java.io.IOException{this.client=client;this.demo=mode.equals("demo");this.prompt=new ClassPathResource("prompts/reply-v1.txt").getContentAsString(StandardCharsets.UTF_8);}
 public Reply generate(Context context){
  if(context.messages().isEmpty())return new Reply("skip","","NO_INPUT");
  if(demo)return new Reply("reply","Слышу тебя 🙂 Расскажешь чуть подробнее?","DEMO_MODEL");
  var data=Json.object();data.set("profile",context.settings());data.put("summary",context.summary());data.set("facts",Json.MAPPER.valueToTree(context.facts()));data.set("messages",Json.MAPPER.valueToTree(context.messages()));
  var action=OpenAiClient.stringSchema();action.set("enum",Json.MAPPER.valueToTree(List.of("reply","skip","handoff")));
  var schema=OpenAiClient.schema(Map.of("action",action,"text",OpenAiClient.stringSchema(),"reason",OpenAiClient.stringSchema()));
  var result=client.structured(context.conversationId(),"reply",context.settings().path("replyModel").asText("gpt-5.4"),"reply-v1",prompt,data,context.imageDataUrls(),schema);
  String mode=result.path("action").asText(),text=result.path("text").asText(),reason=result.path("reason").asText();
  if(!Set.of("reply","skip","handoff").contains(mode)||text.length()>context.settings().path("maxReplyChars").asInt(800)||mode.equals("reply")&&text.isBlank())throw new ApiException(502,"INVALID_MODEL_REPLY");
  return new Reply(mode,text,reason.length()>100?"MODEL_DECISION":reason);
 }
}
