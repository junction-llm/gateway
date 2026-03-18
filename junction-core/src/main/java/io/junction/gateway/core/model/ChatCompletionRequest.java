package io.junction.gateway.core.model;

import java.util.Objects;
import java.util.List;

public record ChatCompletionRequest(
    String model,
    List<Message> messages,
    boolean stream,
    Double temperature,
    Integer maxTokens
) {
    public record ContentPart(
        String type,
        String text,
        ImageUrl imageUrl
    ) {
        public record ImageUrl(String url) {}
        
        public static ContentPart text(String text) {
            return new ContentPart("text", text, null);
        }
        
        public static ContentPart imageUrl(String url) {
            return new ContentPart("image_url", null, new ImageUrl(url));
        }
    }

    public record Message(
        String role,
        List<ContentPart> content
    ) {
        public Message(String role, String content) {
            this(role, List.of(ContentPart.text(content)));
        }

        public boolean hasImageParts() {
            return content != null
                && content.stream().anyMatch(part -> "image_url".equals(part.type())
                    && part.imageUrl() != null
                    && part.imageUrl().url() != null
                    && !part.imageUrl().url().isBlank());
        }

        public List<String> imageUrls() {
            if (content == null || content.isEmpty()) {
                return List.of();
            }

            return content.stream()
                .filter(part -> "image_url".equals(part.type())
                    && part.imageUrl() != null
                    && part.imageUrl().url() != null)
                .map(part -> part.imageUrl().url())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(url -> !url.isBlank())
                .toList();
        }
        
        public String getTextContent() {
            if (content == null || content.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (ContentPart part : content) {
                if ("text".equals(part.type()) && part.text() != null) {
                    if (!sb.isEmpty()) {
                        sb.append(" ");
                    }
                    sb.append(part.text());
                }
            }
            return sb.toString();
        }
    }
}
