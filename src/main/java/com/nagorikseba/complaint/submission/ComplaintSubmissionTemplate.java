package com.nagorikseba.complaint.submission;

import com.nagorikseba.complaint.api.dto.ComplaintSubmissionRequest;
import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.Category;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.service.AttachmentService;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import com.nagorikseba.shared.exception.FileStorageException;
import com.nagorikseba.shared.security.PrincipalContext;
import com.nagorikseba.shared.service.FileStorageService;
import com.nagorikseba.shared.time.Clock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ComplaintSubmissionTemplate {

    private final ComplaintRepository complaintRepository;
    private final WardRepository wardRepository;
    private final MunicipalityRepository municipalityRepository;
    private final AttachmentService attachmentService;
    private final FileStorageService fileStorageService;
    private final PrincipalContext principalContext;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public Complaint submit(ComplaintSubmissionRequest request, User citizen) {
        validate(request, citizen);
        Complaint complaint = createComplaint(request, citizen);
        complaint = complaintRepository.saveAndFlush(complaint);
        saveAttachments(complaint, request);
        afterSubmit(complaint);
        return complaint;
    }

    protected void validate(ComplaintSubmissionRequest request, User citizen) {
        if (request.getPhotos().size() > 5) {
            throw new IllegalArgumentException("You can upload up to 5 photos");
        }
    }

    private Complaint createComplaint(ComplaintSubmissionRequest request, User citizen) {
        Municipality municipality = resolveMunicipality(request, citizen);
        Ward ward = resolveWard(request.getLatitude(), request.getLongitude(), municipality);
        
        Point location = new GeometryFactory().createPoint(
                new Coordinate(request.getLongitude().doubleValue(), request.getLatitude().doubleValue()));
        location.setSRID(4326);

        String referenceCode = generateReferenceCode();

        return Complaint.builder()
                .referenceCode(referenceCode)
                .municipality(municipality)
                .ward(ward)
                .citizen(citizen)
                .title(request.getTitle().trim())
                .description(request.getDescription().trim())
                .category(request.getCategory())
                .status(ComplaintStatus.SUBMITTED)
                .priority(Priority.NORMAL)
                .location(location)
                .locationSource(LocationSource.DEVICE)
                .addressText(request.getAddressText())
                .moderationStatus(ModerationStatus.APPROVED)
                .submittedAt(clock.instant())
                .build();
    }

    protected Municipality resolveMunicipality(ComplaintSubmissionRequest request, User citizen) {
        Set<Long> municipalityIds = principalContext.municipalityIds();
        if (!municipalityIds.isEmpty()) {
            Long municipalityId = municipalityIds.iterator().next();
            return municipalityRepository.findById(municipalityId)
                    .orElseThrow(() -> new IllegalStateException("Municipality not found: " + municipalityId));
        }
        // For anonymous submissions, resolve municipality from location
        // This is a bbox fallback - full PostGIS spatial query comes in Phase 4
        return municipalityRepository.findFirstByIsActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active municipality found"));
    }

    protected Ward resolveWard(BigDecimal latitude, BigDecimal longitude, Municipality municipality) {
        if (latitude == null || longitude == null || municipality == null) {
            return null;
        }
        Point point = new GeometryFactory().createPoint(
                new Coordinate(longitude.doubleValue(), latitude.doubleValue()));
        point.setSRID(4326);
        return wardRepository.findWardContainingPoint(municipality.getId(), point).orElse(null);
    }

    protected String generateReferenceCode() {
        Long seq = (Long) entityManager.createNativeQuery("SELECT nextval('complaint_ref_seq')").getSingleResult();
        int year = java.time.Year.now().getValue();
        return String.format("NS-%d-%06d", year, seq);
    }

    protected void saveAttachments(Complaint complaint, ComplaintSubmissionRequest request) {
        List<Attachment> attachments = attachmentService.saveAttachments(complaint, request.getPhotos());
        attachments.forEach(complaint::addAttachment);
    }

    protected void afterSubmit(Complaint complaint) {
        // SLA instance creation deferred to Phase 5
        // Outbox event will be published by lifecycle service for COMPLAINT_SUBMITTED
    }
}