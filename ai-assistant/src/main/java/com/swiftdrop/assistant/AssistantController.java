package com.swiftdrop.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    public record Message(
            @NotNull @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 2000) String text) { }

    public record ChatRequest(@NotEmpty @Size(max = 20) List<@Valid @NotNull Message> messages) { }

    public record ChatResponse(String reply, List<String> toolsUsed) { }

    public record StatusResponse(boolean enabled, String provider) { }

    private final AssistantService service;
    private final AssistantProperties properties;

    public AssistantController(AssistantService service, AssistantProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /** Lets the frontend choose between the AI assistant and its offline keyword mode. Never reveals the key. */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new StatusResponse(properties.enabled(), properties.enabled() ? "gemini" : null));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (!properties.enabled()) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Assistant not configured",
                    "The AI assistant is not configured on this server.");
        }
        Message last = request.messages().get(request.messages().size() - 1);
        if (!"user".equals(last.role())) {
            return problem(HttpStatus.BAD_REQUEST, "Invalid conversation", "The last message must be from the user.");
        }
        List<AssistantService.Turn> history = request.messages().stream()
                .map(m -> new AssistantService.Turn(m.role(), m.text().trim()))
                .toList();
        try {
            AssistantService.Reply reply = service.chat(history, jwt == null ? null : jwt.getTokenValue());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new ChatResponse(reply.text(), reply.toolsUsed()));
        } catch (GeminiClient.GeminiException e) {
            return problem(e.rateLimited() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY,
                    "Assistant unavailable", e.getMessage());
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "Send 1 to 20 messages; each needs role user or assistant and 1 to 2000 characters of text.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
