package app.chattyx.generation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

public interface ReplyModel {
  record Context(
    UUID conversationId,
    ObjectNode settings,
    String summary,
    List<Map<String, Object>> facts,
    List<Map<String, Object>> messages,
    List<String> imageDataUrls
  ) {}

  record Reply(String action, String text, String reason) {}

  Reply generate(Context context);
}
