package com.swiftdrop.deliveryservice.proof.controller;

import com.swiftdrop.deliveryservice.proof.ProofService;
import com.swiftdrop.deliveryservice.proof.dto.ProofDetailResponse;
import com.swiftdrop.deliveryservice.proof.dto.ProofResponse;
import com.swiftdrop.deliveryservice.proof.dto.UploadProofRequest;
import com.swiftdrop.deliveryservice.proof.storage.RetrievedProofObject;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

/**
 * REST Controller for Courier Proof-of-Delivery metadata ingestion and authorized retrieval.
 * Implements REST contract operation uploadProofMetadata (POST /api/v1/parcels/{parcelId}/proof).
 */
@RestController
@RequestMapping("/api/v1/parcels/{parcelId}/proof")
public class ProofController {

    private final ProofService proofService;

    public ProofController(ProofService proofService) {
        this.proofService = proofService;
    }

    /**
     * Ingests proof of delivery metadata via JSON request matching OpenAPI spec.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProofResponse> uploadProofMetadata(
            @PathVariable("parcelId") UUID parcelId,
            @Valid @RequestBody UploadProofRequest request,
            Authentication authentication) {

        String actorSub = extractSubject(authentication);
        Collection<String> roles = extractRoles(authentication);

        ProofResponse response = proofService.recordProofMetadata(parcelId, request, actorSub, roles);
        return ResponseEntity.created(URI.create("/api/v1/parcels/" + parcelId + "/proof"))
                .body(response);
    }

    /**
     * Uploads proof photo/signature binary directly via multipart form data.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProofResponse> uploadProofMultipart(
            @PathVariable("parcelId") UUID parcelId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "recipientName", required = false) String recipientName,
            @RequestParam(value = "deliveryNote", required = false) String deliveryNote,
            Authentication authentication) throws IOException {

        String actorSub = extractSubject(authentication);
        Collection<String> roles = extractRoles(authentication);

        try (InputStream inputStream = file.getInputStream()) {
            ProofResponse response = proofService.uploadProofContent(
                    parcelId,
                    inputStream,
                    file.getSize(),
                    file.getContentType(),
                    recipientName,
                    deliveryNote,
                    actorSub,
                    roles
            );
            return ResponseEntity.created(URI.create("/api/v1/parcels/" + parcelId + "/proof"))
                    .body(response);
        }
    }

    /**
     * Retrieves proof metadata for authorized actors.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProofDetailResponse> getProofMetadata(
            @PathVariable("parcelId") UUID parcelId,
            Authentication authentication) {

        String actorSub = extractSubject(authentication);
        Collection<String> roles = extractRoles(authentication);

        ProofDetailResponse response = proofService.getProofMetadata(parcelId, actorSub, roles);
        return ResponseEntity.ok(response);
    }

    /**
     * Streams the private proof binary to authorized actors with zero public URL leaks.
     */
    @GetMapping(value = "/content", produces = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<Resource> getProofContent(
            @PathVariable("parcelId") UUID parcelId,
            Authentication authentication) {

        String actorSub = extractSubject(authentication);
        Collection<String> roles = extractRoles(authentication);

        RetrievedProofObject retrieved = proofService.getProofContent(parcelId, actorSub, roles);
        String contentType = retrieved.contentType().orElse("image/jpeg");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentLength(retrieved.contentLength());
        headers.setCacheControl(CacheControl.noStore());

        InputStreamResource resource = new InputStreamResource(retrieved.content()) {
            @Override
            public long contentLength() {
                return retrieved.contentLength();
            }
        };

        return new ResponseEntity<>(resource, headers, HttpStatus.OK);
    }

    private String extractSubject(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return jwtToken.getToken().getSubject();
        }
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        if (authentication != null && authentication.getName() != null) {
            return authentication.getName();
        }
        return "anonymous";
    }

    private Collection<String> extractRoles(Authentication authentication) {
        if (authentication == null) {
            return Collections.emptyList();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
