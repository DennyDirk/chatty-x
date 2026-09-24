package app.chattyx.connections;

import app.chattyx.shared.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ChannelRegistry {

  private final List<MessagingAdapter> adapters;
  private final Db db;

  public ChannelRegistry(List<MessagingAdapter> adapters, Db db) {
    this.adapters = adapters;
    this.db = db;
  }

  public List<MessagingAdapter> all() {
    return adapters;
  }

  public MessagingAdapter byName(String name) {
    return adapters
      .stream()
      .filter(a -> a.name().equals(name))
      .findFirst()
      .orElseThrow(() -> new ApiException(400, "ADAPTER_UNAVAILABLE"));
  }

  public MessagingAdapter forConnection(UUID id) {
    return byName(db.one("SELECT adapter FROM connection WHERE id=?", id).get("adapter").toString());
  }
}
