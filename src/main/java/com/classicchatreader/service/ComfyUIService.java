package com.classicchatreader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

@Service
public class ComfyUIService {

  private static final Logger log = LoggerFactory.getLogger(ComfyUIService.class);

  @Value("${comfyui.base-url}")
  private String comfyuiBaseUrl;

  @Value("${comfyui.workflow-timeout}")
  private long workflowTimeout;

  @Value("${comfyui.checkpoint}")
  private String checkpoint;

  @Value("${illustration.cache-dir}")
  private String cacheDir;

  @Value("${illustration.image-width}")
  private int imageWidth;

  @Value("${illustration.image-height}")
  private int imageHeight;

  @Value("${illustration.sampler-steps}")
  private int samplerSteps;

  @Value("${illustration.cfg-scale}")
  private int cfgScale;

  @Value("${character.portrait.width:512}")
  private int portraitWidth;

  @Value("${character.portrait.height:640}")
  private int portraitHeight;

  @Value("${character.portrait.cache-dir:./data/character-portraits}")
  private String portraitCacheDir;

  private WebClient webClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Random random = new Random();
  private final ConcurrentMap<String, String> illustrationCacheKeys = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, String> portraitCacheKeys = new ConcurrentHashMap<>();

  @PostConstruct
  public void init() throws IOException {
    // Configure WebClient with larger buffer for image downloads (16MB)
    this.webClient = WebClient.builder()
        .baseUrl(comfyuiBaseUrl)
        .codecs(configurer -> configurer
            .defaultCodecs()
            .maxInMemorySize(16 * 1024 * 1024))
        .build();

    // Ensure cache directories exist
    Path cachePath = Paths.get(cacheDir);
    if (!Files.exists(cachePath)) {
      Files.createDirectories(cachePath);
      log.info("Created illustration cache directory: {}", cacheDir);
    }

    Path portraitCachePath = Paths.get(portraitCacheDir);
    if (!Files.exists(portraitCachePath)) {
      Files.createDirectories(portraitCachePath);
      log.info("Created portrait cache directory: {}", portraitCacheDir);
    }

    log.info("ComfyUI service initialized with endpoint: {}", comfyuiBaseUrl);
  }

  public boolean isAvailable() {
    try {
      webClient.get()
          .uri("/system_stats")
          .retrieve()
          .bodyToMono(String.class)
          .block(Duration.ofSeconds(3));
      return true;
    } catch (Exception e) {
      log.debug("ComfyUI not available: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Submit a workflow to ComfyUI for image generation.
   *
   * @param positivePrompt The positive prompt for the image
   * @param outputFilename The filename prefix for the output image
   * @return The prompt_id for polling
   */
  public String submitWorkflow(String positivePrompt, String outputFilename, String cacheKey) throws Exception {
    ObjectNode workflow = buildWorkflow(positivePrompt, outputFilename);

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.set("prompt", workflow);

    String response = webClient.post()
        .uri("/prompt")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(objectMapper.writeValueAsString(requestBody))
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(10));

    JsonNode responseNode = objectMapper.readTree(response);
    String promptId = responseNode.get("prompt_id").asText();
    if (cacheKey != null && !cacheKey.isBlank()) {
      illustrationCacheKeys.put(promptId, cacheKey);
    }
    log.info("Submitted workflow to ComfyUI, prompt_id: {}", promptId);
    return promptId;
  }

  /**
   * Poll for workflow completion.
   *
   * @param promptId The prompt ID from submitWorkflow
   * @return The generated image filename, or null if still processing
   */
  public IllustrationResult pollForCompletion(String promptId) throws Exception {
    long startTime = System.currentTimeMillis();
    long pollInterval = 2000; // 2 seconds
    String cacheKey = illustrationCacheKeys.remove(promptId);

    while (System.currentTimeMillis() - startTime < workflowTimeout) {
      String response = webClient.get()
          .uri("/history/{promptId}", promptId)
          .retrieve()
          .bodyToMono(String.class)
          .block(Duration.ofSeconds(5));

      JsonNode historyNode = objectMapper.readTree(response);
      JsonNode promptHistory = historyNode.get(promptId);

      if (promptHistory != null && !promptHistory.isNull()) {
        // Check if there's an error
        JsonNode status = promptHistory.get("status");
        if (status != null) {
          boolean statusCompleted = status.has("completed") && status.get("completed").asBoolean();
          if (status.has("status_str") && "error".equals(status.get("status_str").asText())) {
            String errorMsg = "ComfyUI workflow error";
            if (status.has("messages")) {
              errorMsg = status.get("messages").toString();
            }
            return new IllustrationResult(false, null, errorMsg);
          }
        }

        // Check for outputs
        JsonNode outputs = promptHistory.get("outputs");
        if (outputs != null && outputs.size() > 0) {
          // Find the SaveImage node output (node 9 in our workflow)
          for (JsonNode nodeOutput : outputs) {
            if (nodeOutput.has("images")) {
              JsonNode images = nodeOutput.get("images");
              if (images.isArray() && images.size() > 0) {
                JsonNode imageInfo = images.get(0);
                String filename = imageInfo.get("filename").asText();
                String subfolder = imageInfo.has("subfolder") ? imageInfo.get("subfolder").asText() : "";

                // Download the image to our cache
                String cachedPath = downloadImage(filename, subfolder, cacheKey, promptId);
                return new IllustrationResult(true, cachedPath, null);
              }
            }
          }
        }
      }

      Thread.sleep(pollInterval);
    }

    throw new TimeoutException("Workflow timed out after " + workflowTimeout + "ms");
  }

  /**
   * Download generated image from ComfyUI and save to cache.
   */
  private String downloadImage(String filename, String subfolder, String cacheKey, String promptId) throws Exception {
    String uri = "/view?filename=" + filename;
    if (subfolder != null && !subfolder.isEmpty()) {
      uri += "&subfolder=" + subfolder;
    }

    byte[] imageData = webClient.get()
        .uri(uri)
        .retrieve()
        .bodyToMono(byte[].class)
        .block(Duration.ofSeconds(30));

    // Save to cache directory with prompt ID as filename
    String cachedFilename = resolveCacheFilename(cacheKey, promptId);
    Path cachedPath = Paths.get(cacheDir, cachedFilename);
    Files.createDirectories(cachedPath.getParent());
    Files.write(cachedPath, imageData);

    log.info("Downloaded and cached image: {}", cachedPath);
    return cachedFilename;
  }

  /**
   * Get image bytes from cache.
   */
  public byte[] getImage(String filename) {
    try {
      Path imagePath = safeResolve(cacheDir, filename);
      if (Files.exists(imagePath)) {
        return Files.readAllBytes(imagePath);
      }
    } catch (IOException e) {
      log.error("Failed to read cached image: {}", filename, e);
    }
    return null;
  }

  /**
   * Build the ComfyUI workflow JSON in API format.
   */
  private ObjectNode buildWorkflow(String positivePrompt, String outputFilename) {
    ObjectNode workflow = objectMapper.createObjectNode();

    // Node 4: CheckpointLoaderSimple
    ObjectNode node4 = objectMapper.createObjectNode();
    node4.put("class_type", "CheckpointLoaderSimple");
    ObjectNode node4Inputs = objectMapper.createObjectNode();
    node4Inputs.put("ckpt_name", checkpoint);
    node4.set("inputs", node4Inputs);
    workflow.set("4", node4);

    // Node 5: EmptyLatentImage
    ObjectNode node5 = objectMapper.createObjectNode();
    node5.put("class_type", "EmptyLatentImage");
    ObjectNode node5Inputs = objectMapper.createObjectNode();
    node5Inputs.put("width", imageWidth);
    node5Inputs.put("height", imageHeight);
    node5Inputs.put("batch_size", 1);
    node5.set("inputs", node5Inputs);
    workflow.set("5", node5);

    // Node 6: CLIPTextEncode (positive)
    ObjectNode node6 = objectMapper.createObjectNode();
    node6.put("class_type", "CLIPTextEncode");
    ObjectNode node6Inputs = objectMapper.createObjectNode();
    node6Inputs.put("text", positivePrompt);
    ArrayNode clipLink6 = objectMapper.createArrayNode();
    clipLink6.add("4").add(1);
    node6Inputs.set("clip", clipLink6);
    node6.set("inputs", node6Inputs);
    workflow.set("6", node6);

    // Node 7: CLIPTextEncode (negative)
    ObjectNode node7 = objectMapper.createObjectNode();
    node7.put("class_type", "CLIPTextEncode");
    ObjectNode node7Inputs = objectMapper.createObjectNode();
    node7Inputs.put("text", "text, watermark, blurry, bad quality, deformed, ugly, low resolution");
    ArrayNode clipLink7 = objectMapper.createArrayNode();
    clipLink7.add("4").add(1);
    node7Inputs.set("clip", clipLink7);
    node7.set("inputs", node7Inputs);
    workflow.set("7", node7);

    // Node 3: KSampler
    ObjectNode node3 = objectMapper.createObjectNode();
    node3.put("class_type", "KSampler");
    ObjectNode node3Inputs = objectMapper.createObjectNode();
    node3Inputs.put("seed", random.nextLong() & Long.MAX_VALUE);
    node3Inputs.put("steps", samplerSteps);
    node3Inputs.put("cfg", cfgScale);
    node3Inputs.put("sampler_name", "euler");
    node3Inputs.put("scheduler", "normal");
    node3Inputs.put("denoise", 1.0);
    ArrayNode modelLink = objectMapper.createArrayNode();
    modelLink.add("4").add(0);
    node3Inputs.set("model", modelLink);
    ArrayNode positiveLink = objectMapper.createArrayNode();
    positiveLink.add("6").add(0);
    node3Inputs.set("positive", positiveLink);
    ArrayNode negativeLink = objectMapper.createArrayNode();
    negativeLink.add("7").add(0);
    node3Inputs.set("negative", negativeLink);
    ArrayNode latentLink = objectMapper.createArrayNode();
    latentLink.add("5").add(0);
    node3Inputs.set("latent_image", latentLink);
    node3.set("inputs", node3Inputs);
    workflow.set("3", node3);

    // Node 8: VAEDecode
    ObjectNode node8 = objectMapper.createObjectNode();
    node8.put("class_type", "VAEDecode");
    ObjectNode node8Inputs = objectMapper.createObjectNode();
    ArrayNode samplesLink = objectMapper.createArrayNode();
    samplesLink.add("3").add(0);
    node8Inputs.set("samples", samplesLink);
    ArrayNode vaeLink = objectMapper.createArrayNode();
    vaeLink.add("4").add(2);
    node8Inputs.set("vae", vaeLink);
    node8.set("inputs", node8Inputs);
    workflow.set("8", node8);

    // Node 9: SaveImage
    ObjectNode node9 = objectMapper.createObjectNode();
    node9.put("class_type", "SaveImage");
    ObjectNode node9Inputs = objectMapper.createObjectNode();
    node9Inputs.put("filename_prefix", outputFilename);
    ArrayNode imagesLink = objectMapper.createArrayNode();
    imagesLink.add("8").add(0);
    node9Inputs.set("images", imagesLink);
    node9.set("inputs", node9Inputs);
    workflow.set("9", node9);

    return workflow;
  }

  public record IllustrationResult(boolean success, String filename, String errorMessage) {
  }

  // ===== Character Portrait Methods =====

  /**
   * Submit a portrait workflow to ComfyUI for character portrait generation.
   * Uses portrait-specific dimensions (512x640 by default).
   */
  public String submitPortraitWorkflow(String positivePrompt, String outputFilename, String cacheKey) throws Exception {
    ObjectNode workflow = buildPortraitWorkflow(positivePrompt, outputFilename);

    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.set("prompt", workflow);

    String response = webClient.post()
        .uri("/prompt")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(objectMapper.writeValueAsString(requestBody))
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(10));

    JsonNode responseNode = objectMapper.readTree(response);
    String promptId = responseNode.get("prompt_id").asText();
    if (cacheKey != null && !cacheKey.isBlank()) {
      portraitCacheKeys.put(promptId, cacheKey);
    }
    log.info("Submitted portrait workflow to ComfyUI, prompt_id: {}", promptId);
    return promptId;
  }

  /**
   * Poll for portrait workflow completion.
   */
  public IllustrationResult pollForPortraitCompletion(String promptId) throws Exception {
    long startTime = System.currentTimeMillis();
    long pollInterval = 2000;
    String cacheKey = portraitCacheKeys.remove(promptId);

    while (System.currentTimeMillis() - startTime < workflowTimeout) {
      String response = webClient.get()
          .uri("/history/{promptId}", promptId)
          .retrieve()
          .bodyToMono(String.class)
          .block(Duration.ofSeconds(5));

      JsonNode historyNode = objectMapper.readTree(response);
      JsonNode promptHistory = historyNode.get(promptId);

      if (promptHistory != null && !promptHistory.isNull()) {
        JsonNode status = promptHistory.get("status");
        if (status != null) {
          if (status.has("status_str") && "error".equals(status.get("status_str").asText())) {
            String errorMsg = "ComfyUI workflow error";
            if (status.has("messages")) {
              errorMsg = status.get("messages").toString();
            }
            return new IllustrationResult(false, null, errorMsg);
          }
        }

        JsonNode outputs = promptHistory.get("outputs");
        if (outputs != null && outputs.size() > 0) {
          for (JsonNode nodeOutput : outputs) {
            if (nodeOutput.has("images")) {
              JsonNode images = nodeOutput.get("images");
              if (images.isArray() && images.size() > 0) {
                JsonNode imageInfo = images.get(0);
                String filename = imageInfo.get("filename").asText();
                String subfolder = imageInfo.has("subfolder") ? imageInfo.get("subfolder").asText() : "";

                String cachedPath = downloadPortraitImage(filename, subfolder, cacheKey, promptId);
                return new IllustrationResult(true, cachedPath, null);
              }
            }
          }
        }
      }

      Thread.sleep(pollInterval);
    }

    throw new TimeoutException("Portrait workflow timed out after " + workflowTimeout + "ms");
  }

  /**
   * Download generated portrait image from ComfyUI and save to portrait cache.
   */
  private String downloadPortraitImage(String filename, String subfolder, String cacheKey, String promptId) throws Exception {
    String uri = "/view?filename=" + filename;
    if (subfolder != null && !subfolder.isEmpty()) {
      uri += "&subfolder=" + subfolder;
    }

    byte[] imageData = webClient.get()
        .uri(uri)
        .retrieve()
        .bodyToMono(byte[].class)
        .block(Duration.ofSeconds(30));

    String cachedFilename = resolveCacheFilename(cacheKey, promptId);
    Path cachedPath = Paths.get(portraitCacheDir, cachedFilename);
    Files.createDirectories(cachedPath.getParent());
    Files.write(cachedPath, imageData);

    log.info("Downloaded and cached portrait: {}", cachedPath);
    return cachedFilename;
  }

  /**
   * Get portrait image bytes from cache.
   */
  public byte[] getPortraitImage(String filename) {
    try {
      Path imagePath = safeResolve(portraitCacheDir, filename);
      if (Files.exists(imagePath)) {
        return Files.readAllBytes(imagePath);
      }
    } catch (IOException e) {
      log.error("Failed to read cached portrait: {}", filename, e);
    }
    return null;
  }

  /**
   * Delete a portrait image from the cache.
   */
  public boolean deletePortraitFile(String filename) {
    if (filename == null || filename.isBlank()) {
      return false;
    }
    try {
      Path imagePath = safeResolve(portraitCacheDir, filename);
      if (Files.exists(imagePath)) {
        Files.delete(imagePath);
        log.info("Deleted portrait file: {}", filename);
        return true;
      }
    } catch (IOException e) {
      log.error("Failed to delete portrait file: {}", filename, e);
    }
    return false;
  }

  /**
   * Build the ComfyUI workflow JSON for portrait generation (different dimensions).
   */
  private ObjectNode buildPortraitWorkflow(String positivePrompt, String outputFilename) {
    ObjectNode workflow = objectMapper.createObjectNode();

    // Node 4: CheckpointLoaderSimple
    ObjectNode node4 = objectMapper.createObjectNode();
    node4.put("class_type", "CheckpointLoaderSimple");
    ObjectNode node4Inputs = objectMapper.createObjectNode();
    node4Inputs.put("ckpt_name", checkpoint);
    node4.set("inputs", node4Inputs);
    workflow.set("4", node4);

    // Node 5: EmptyLatentImage (portrait dimensions)
    ObjectNode node5 = objectMapper.createObjectNode();
    node5.put("class_type", "EmptyLatentImage");
    ObjectNode node5Inputs = objectMapper.createObjectNode();
    node5Inputs.put("width", portraitWidth);
    node5Inputs.put("height", portraitHeight);
    node5Inputs.put("batch_size", 1);
    node5.set("inputs", node5Inputs);
    workflow.set("5", node5);

    // Node 6: CLIPTextEncode (positive)
    ObjectNode node6 = objectMapper.createObjectNode();
    node6.put("class_type", "CLIPTextEncode");
    ObjectNode node6Inputs = objectMapper.createObjectNode();
    node6Inputs.put("text", positivePrompt);
    ArrayNode clipLink6 = objectMapper.createArrayNode();
    clipLink6.add("4").add(1);
    node6Inputs.set("clip", clipLink6);
    node6.set("inputs", node6Inputs);
    workflow.set("6", node6);

    // Node 7: CLIPTextEncode (negative - portrait-specific)
    ObjectNode node7 = objectMapper.createObjectNode();
    node7.put("class_type", "CLIPTextEncode");
    ObjectNode node7Inputs = objectMapper.createObjectNode();
    node7Inputs.put("text", "text, watermark, blurry, bad quality, deformed, ugly, low resolution, disfigured face, extra limbs");
    ArrayNode clipLink7 = objectMapper.createArrayNode();
    clipLink7.add("4").add(1);
    node7Inputs.set("clip", clipLink7);
    node7.set("inputs", node7Inputs);
    workflow.set("7", node7);

    // Node 3: KSampler
    ObjectNode node3 = objectMapper.createObjectNode();
    node3.put("class_type", "KSampler");
    ObjectNode node3Inputs = objectMapper.createObjectNode();
    node3Inputs.put("seed", random.nextLong() & Long.MAX_VALUE);
    node3Inputs.put("steps", samplerSteps);
    node3Inputs.put("cfg", cfgScale);
    node3Inputs.put("sampler_name", "euler");
    node3Inputs.put("scheduler", "normal");
    node3Inputs.put("denoise", 1.0);
    ArrayNode modelLink = objectMapper.createArrayNode();
    modelLink.add("4").add(0);
    node3Inputs.set("model", modelLink);
    ArrayNode positiveLink = objectMapper.createArrayNode();
    positiveLink.add("6").add(0);
    node3Inputs.set("positive", positiveLink);
    ArrayNode negativeLink = objectMapper.createArrayNode();
    negativeLink.add("7").add(0);
    node3Inputs.set("negative", negativeLink);
    ArrayNode latentLink = objectMapper.createArrayNode();
    latentLink.add("5").add(0);
    node3Inputs.set("latent_image", latentLink);
    node3.set("inputs", node3Inputs);
    workflow.set("3", node3);

    // Node 8: VAEDecode
    ObjectNode node8 = objectMapper.createObjectNode();
    node8.put("class_type", "VAEDecode");
    ObjectNode node8Inputs = objectMapper.createObjectNode();
    ArrayNode samplesLink = objectMapper.createArrayNode();
    samplesLink.add("3").add(0);
    node8Inputs.set("samples", samplesLink);
    ArrayNode vaeLink = objectMapper.createArrayNode();
    vaeLink.add("4").add(2);
    node8Inputs.set("vae", vaeLink);
    node8.set("inputs", node8Inputs);
    workflow.set("8", node8);

    // Node 9: SaveImage
    ObjectNode node9 = objectMapper.createObjectNode();
    node9.put("class_type", "SaveImage");
    ObjectNode node9Inputs = objectMapper.createObjectNode();
    node9Inputs.put("filename_prefix", outputFilename);
    ArrayNode imagesLink = objectMapper.createArrayNode();
    imagesLink.add("8").add(0);
    node9Inputs.set("images", imagesLink);
    node9.set("inputs", node9Inputs);
    workflow.set("9", node9);

    return workflow;
  }

  private String resolveCacheFilename(String cacheKey, String promptId) {
    String resolved = (cacheKey == null || cacheKey.isBlank()) ? promptId + ".png" : cacheKey;
    return resolved.endsWith(".png") ? resolved : resolved + ".png";
  }

  private Path safeResolve(String baseDir, String filename) {
    Path basePath = Paths.get(baseDir).toAbsolutePath().normalize();
    Path resolved = basePath.resolve(filename).normalize();
    if (!resolved.startsWith(basePath)) {
      return basePath.resolve(Paths.get(filename).getFileName().toString());
    }
    return resolved;
  }
}
