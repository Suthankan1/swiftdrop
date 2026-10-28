package com.swiftdrop.deliveryservice.query.service;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignment;
import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.core.ParcelNotFoundException;
import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.query.ParcelProjection;
import com.swiftdrop.deliveryservice.query.ParcelQueryService;
import com.swiftdrop.deliveryservice.query.dto.PagedResponse;
import com.swiftdrop.deliveryservice.query.dto.ParcelDetailResponse;
import com.swiftdrop.deliveryservice.query.dto.ParcelStatusHistoryResponse;
import com.swiftdrop.deliveryservice.query.exception.ParcelAccessDeniedException;
import com.swiftdrop.deliveryservice.security.KeycloakRoleExtractor;
import com.swiftdrop.deliveryservice.status.StatusHistory;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Production implementation of ParcelQueryService and authorized parcel REST read provider.
 * Guarantees strictly read-only execution, zero mutation side effects, and bounded pagination.
 */
@Service
@Transactional(readOnly = true)
public class DefaultParcelQueryService implements ParcelQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ParcelRepository parcelRepository;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final StatusHistoryRepository historyRepository;
    private final ParcelAuthorizationService authorizationService;

    public DefaultParcelQueryService(
            ParcelRepository parcelRepository,
            DeliveryAssignmentRepository assignmentRepository,
            StatusHistoryRepository historyRepository,
            ParcelAuthorizationService authorizationService) {
        this.parcelRepository = parcelRepository;
        this.assignmentRepository = assignmentRepository;
        this.historyRepository = historyRepository;
        this.authorizationService = authorizationService;
    }

    // --- ParcelQueryService Interface Implementation (gRPC & GraphQL Projections) ---

    @Override
    public Optional<ParcelProjection> findParcelById(UUID parcelId) {
        if (parcelId == null) {
            return Optional.empty();
        }
        return parcelRepository.findById(parcelId).map(this::mapToProjection);
    }

    @Override
    public Optional<ParcelProjection> findParcelByTrackingNumber(String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return Optional.empty();
        }
        return parcelRepository.findByTrackingNumber(trackingNumber.trim()).map(this::mapToProjection);
    }

    // --- Authorized REST Reads Implementation ---

    /**
     * Retrieves full authorized parcel details matching GET /api/v1/parcels/{parcelId}.
     * Enforces object-level authorization across Customer, Merchant, Courier, and Staff actors.
     */
    public ParcelDetailResponse getParcelDetail(UUID parcelId, Jwt jwt) {
        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(jwt, "jwt cannot be null");

        Parcel parcel = parcelRepository.findById(parcelId)
                .orElseThrow(() -> new ParcelNotFoundException(parcelId));

        if (!authorizationService.isAuthorizedToRead(jwt, parcel)) {
            throw new ParcelAccessDeniedException(parcelId);
        }

        return mapToDetailResponse(parcel);
    }

    /**
     * Retrieves append-only status transition history for a parcel in deterministic version order.
     * Enforces identical object-level authorization before returning historical records.
     */
    public PagedResponse<ParcelStatusHistoryResponse> getParcelHistory(UUID parcelId, Jwt jwt, Pageable pageable) {
        Objects.requireNonNull(parcelId, "parcelId cannot be null");
        Objects.requireNonNull(jwt, "jwt cannot be null");

        Parcel parcel = parcelRepository.findById(parcelId)
                .orElseThrow(() -> new ParcelNotFoundException(parcelId));

        if (!authorizationService.isAuthorizedToRead(jwt, parcel)) {
            throw new ParcelAccessDeniedException(parcelId);
        }

        Pageable safePageable = sanitizePageable(pageable, Sort.by(Sort.Direction.ASC, "resultingVersion", "historyId"));
        Page<StatusHistory> historyPage = historyRepository.findByParcelIdOrderByResultingVersionAsc(parcelId, safePageable);

        Page<ParcelStatusHistoryResponse> dtoPage = historyPage.map(h -> new ParcelStatusHistoryResponse(
                h.getHistoryId(),
                h.getParcelId(),
                h.getPreviousStatus(),
                h.getNewStatus(),
                h.getReason(),
                h.getNote(),
                h.getProofId(),
                h.getResultingVersion(),
                h.getOccurredAt()
        ));

        return PagedResponse.of(dtoPage);
    }

    /**
     * Lists parcels authorized for the calling identity (Customer or Merchant).
     */
    public PagedResponse<ParcelDetailResponse> listParcelsForCaller(Jwt jwt, Pageable pageable) {
        Objects.requireNonNull(jwt, "jwt cannot be null");

        String callerSub = jwt.getSubject();
        List<String> roles = KeycloakRoleExtractor.extractApprovedRoles(jwt);
        Pageable safePageable = sanitizePageable(pageable, Sort.by(Sort.Direction.DESC, "createdAt", "parcelId"));

        Page<Parcel> parcels;
        if (roles.contains("ADMIN") || roles.contains("DISPATCHER")) {
            parcels = parcelRepository.findAll(safePageable);
        } else if (roles.contains("CUSTOMER") && callerSub != null) {
            parcels = parcelRepository.findBySenderSubOrderByCreatedAtDesc(callerSub, safePageable);
        } else if (roles.contains("MERCHANT")) {
            Object merchantClaim = jwt.getClaim("merchant_id");
            if (merchantClaim != null) {
                try {
                    UUID merchantId = UUID.fromString(merchantClaim.toString());
                    parcels = parcelRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, safePageable);
                } catch (IllegalArgumentException e) {
                    parcels = Page.empty(safePageable);
                }
            } else {
                parcels = Page.empty(safePageable);
            }
        } else {
            parcels = Page.empty(safePageable);
        }

        Page<ParcelDetailResponse> dtoPage = parcels.map(this::mapToDetailResponse);
        return PagedResponse.of(dtoPage);
    }

    // --- Helpers ---

    private ParcelProjection mapToProjection(Parcel parcel) {
        String activeCourier = assignmentRepository.findByParcelIdAndActiveTrue(parcel.getParcelId())
                .map(DeliveryAssignment::getCourierSub)
                .orElse(null);

        return new ParcelProjection(
                parcel.getParcelId(),
                parcel.getTrackingNumber(),
                parcel.getStatus(),
                parcel.getVersion(),
                parcel.getAmountMinorUnits(),
                parcel.getCurrency(),
                parcel.getSenderSub(),
                parcel.getMerchantId(),
                activeCourier,
                parcel.getCreatedAt()
        );
    }

    private ParcelDetailResponse mapToDetailResponse(Parcel parcel) {
        String activeCourier = assignmentRepository.findByParcelIdAndActiveTrue(parcel.getParcelId())
                .map(DeliveryAssignment::getCourierSub)
                .orElse(null);

        double weight = parcel.getWeightKg() != null ? parcel.getWeightKg().doubleValue() : 0.0;
        long amount = parcel.getAmountMinorUnits() != null ? parcel.getAmountMinorUnits() : 0L;
        String currency = parcel.getCurrency() != null ? parcel.getCurrency() : "LKR";
        String rateVersion = parcel.getRateVersion() != null ? parcel.getRateVersion() : "";

        return new ParcelDetailResponse(
                parcel.getParcelId(),
                parcel.getTrackingNumber(),
                parcel.getSenderSub(),
                parcel.getMerchantId(),
                parcel.getStatus(),
                parcel.getVersion(),
                weight,
                "STANDARD", // Non-null dimensions for consumer contract
                amount,
                currency,
                rateVersion,
                parcel.getPickupAddress() != null ? parcel.getPickupAddress() : "",
                parcel.getDropoffAddress() != null ? parcel.getDropoffAddress() : "",
                activeCourier,
                parcel.getCreatedAt()
        );
    }

    private Pageable sanitizePageable(Pageable pageable, Sort fallbackSort) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE, fallbackSort);
        }
        int page = Math.max(0, pageable.getPageNumber());
        int size = pageable.getPageSize();
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        } else if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : fallbackSort;
        return PageRequest.of(page, size, sort);
    }
}
