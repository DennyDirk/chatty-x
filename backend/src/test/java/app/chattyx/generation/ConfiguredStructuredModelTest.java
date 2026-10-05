package app.chattyx.generation;

import static org.mockito.Mockito.*;

import app.chattyx.personas.SettingsService;
import app.chattyx.shared.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfiguredStructuredModelTest {

  @Test
  void bothKindsUseSelectedProviderAndNeverFallBackToCloud() {
    var settings = mock(SettingsService.class);
    var openai = mock(OpenAiClient.class);
    var ollama = mock(OllamaClient.class);
    var router = new ConfiguredStructuredModel(settings, openai, ollama);
    when(settings.localModel()).thenReturn(true);
    var data = Json.object();
    for (String kind : List.of("reply", "memory")) {
      router.structured(null, kind, "cloud-model", "v1", "rules", data, List.of(), data);
      verify(ollama).structured(null, kind, "qwen3:8b", "v1", "rules", data, List.of(), data);
    }
    verifyNoInteractions(openai);
    when(ollama.structured(null, "reply", "qwen3:8b", "v1", "rules", data, List.of(), data)).thenThrow(
      new app.chattyx.shared.ApiException(502, "LOCAL_MODEL_UNAVAILABLE")
    );
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
      router.structured(null, "reply", "cloud-model", "v1", "rules", data, List.of(), data)
    ).isInstanceOf(app.chattyx.shared.ApiException.class);
    verifyNoInteractions(openai);
    when(settings.localModel()).thenReturn(false);
    router.structured(null, "reply", "gpt-5.4", "v1", "rules", data, List.of(), data);
    verify(openai).structured(null, "reply", "gpt-5.4", "v1", "rules", data, List.of(), data);
  }
}
