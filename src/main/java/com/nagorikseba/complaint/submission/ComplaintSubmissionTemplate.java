package com.nagorikseba.complaint.submission;

import com.nagorikseba.complaint.api.dto.ComplaintSubmissionRequest;
import com.nagorikseba.complaint.domain.Attachment;
import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.complaint.domain.enums.ComplaintStatus;
import com.nagorikseba.complaint.domain.enums.LocationSource;
import com.nagorikseba.complaint.domain.enums.ModerationStatus;
import com.nagorikseba.complaint.domain.enums.Priority;
import com.nagorikseba.complaint.lifecycle.ComplaintLifecycleService;
import com.nagorikseba.complaint.repo.ComplaintRepository;
import com.nagorikseba.complaint.service.AttachmentService;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.municipality.entity.Municipality;
import com.nagorikseba.municipality.entity.Ward;
import com.nagorikseba.municipality.repository.MunicipalityRepository;
import com.nagorikseba.municipality.repository.WardRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Year;
import java.util.List;
import java.util.Optional;

/**
 * The fixed skeleton of complaint submission (§7.5, Template Method).
 *
 * <p>{@link #submit} defines the order every submission follows and is
 * {@code final} — subclasses vary the steps, never the sequence. That matters
 * because the sequence encodes correctness: the idempotency lookup has to precede
 * the insert, the insert has to precede attachment staging (attachments need a
 * reference code and a row to hang off), and the SUBMIT audit row has to be
 * written in the same transaction as the complaint it describes.
 *
 * <pre>
 *   validate → persist → saveAttachments → resolveWard → afterSubmit
 * </pre>
 *
 * <p>Ward resolution runs after the insert on purpose: the spatial lookup is not
 * needed to make the row valid ({@code ward_id} is nullable, and a complaint
 * outside every known boundary is still a real complaint), so it is kept off the
 * path that decides whether the submission succeeds at all.
 *
 * <p>Concrete subclasses: {@link StandardComplaintSubmission} for authenticated
 * citizens, {@link AnonymousComplaintSubmission} for phone-only reports.
 */
public abstract class ComplaintSubmissionTemplate {

    protected final ComplaintRepository complaintRepository;
    protected final WardRepository wardRepository;
    protected final MunicipalityRepository municipalityRepository;
    protected final AttachmentService attachmentService;
    protected final ComplaintLifecycleService lifecycleService;
    protected final Clock clock;

    @PersistenceContext
    protected EntityManager entityManager;

    /**
     * Explicit rather than Lombok-generated: {@code @RequiredArgsConstructor} on a
     * subclass emits an implicit {@code super()} call, which cannot exist when the
     * parent has only this constructor. Subclasses pass these through by hand.
     */
    protected ComplaintSubmissionTemplate(ComplaintRepository complaintRepository,
                                          WardRepository wardRepository,
                                          MunicipalityRepository municipalityRepository,
                                          AttachmentService attachmentService,
                                          ComplaintLifecycleService lifecycleService,
                                          Clock clock) {
        this.complaintRepository = complaintRepository;
        this.wardRepository = wardRepository;
        this.municipalityRepository = municipalityRepository;
        this.attachmentService = attachmentService;
        this.lifecycleService = lifecycleService;
        this.clock = clock;
    }

    /** WGS84 — the SRID the {@code geography(Point,4326)} column and Leaflet both speak. */
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Transactional
    public final Complaint submit(ComplaintSubmissionRequest request, User citizen) {
        // R3: a replayed Idempotency-Key returns the original complaint. Checked before
        // anything is written, so a retry after a dropped response costs one SELECT
        // instead of creating a duplicate report the citizen then has to cancel.
        Optional<Complaint> replay = findReplay(request);
        if (replay.isPresent()) {
            return replay.get();
        }

        validate(request, citizen);
        Complaint complaint = persist(request, citizen);
        saveAttachments(complaint, request, citizen);
        resolveWard(complaint);
        afterSubmit(complaint, request);

        // The SUBMIT edge and its outbox event, written in this transaction so the
        // timeline can never disagree with the complaint's existence.
        lifecycleService.recordSubmission(complaint, citizen, complaint.getSubmittedAt());
        return complaint;
    }

    private Optional<Complaint> findReplay(ComplaintSubmissionRequest request) {
        String key = request.getIdempotencyKey();
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return complaintRepository.findBySubmissionIdempotencyKey(key.trim());
    }

    // ------------------------------------------------------------------ hook 1: validate

    /** Subclasses add their own preconditions and should call {@code super} first. */
    protected void validate(ComplaintSubmissionRequest request, User citizen) {
        if (request.getPhotos() == null || request.getPhotos().isEmpty()) {
            throw new IllegalArgumentException("Attach at least one photo of the issue");
        }
        if (request.getPhotos().size() > 5) {
            throw new IllegalArgumentException("You can upload up to 5 photos");
        }
    }

    // ------------------------------------------------------------------ hook 2: persist

    protected Complaint persist(ComplaintSubmissionRequest request, User citizen) {
        Point location = toPoint(request.getLatitude(), request.getLongitude());
        Municipality municipality = resolveMunicipality(request, citizen, location);
        Instant now = clock.instant();
        String key = request.getIdempotencyKey();

        Complaint complaint = Complaint.builder()
                .referenceCode(nextReferenceCode())
                .municipality(municipality)
                .citizen(citizen)
                .anonymousContactPhone(anonymousContactPhone(request, citizen))
                .title(request.getTitle().trim())
                .description(request.getDescription().trim())
                .category(request.getCategory())
                .status(ComplaintStatus.SUBMITTED)
                .priority(initialPriority())
                .location(location)
                .locationSource(resolveLocationSource(request))
                .addressText(trimToNull(request.getAddressText()))
                .publicVisible(true)
                .moderationStatus(initialModerationStatus())
                .submissionIdempotencyKey(key == null || key.isBlank() ? null : key.trim())
                .submittedAt(now)
                .build();

        // saveAndFlush, not save: the attachment step needs the generated id, and a
        // duplicate idempotency key must surface here as a constraint violation rather
        // than at an unrelated flush later.
        return complaintRepository.saveAndFlush(complaint);
    }

    // ------------------------------------------------------------------ hook 3: attachments

    protected void saveAttachments(Complaint complaint, ComplaintSubmissionRequest request, User citizen) {
        List<Attachment> attachments = attachmentService.saveAttachments(complaint, request.getPhotos(), citizen);
        attachments.forEach(complaint::addAttachment);
    }

    // ------------------------------------------------------------------ hook 4: ward

    /**
     * Point-in-polygon against ward boundaries, scoped to the complaint's
     * municipality. Leaves the ward null when the point falls outside every known
     * boundary — routing is Phase 4's problem, and refusing the complaint over a gap
     * in our own map data would be the wrong answer for the citizen.
     */
    protected void resolveWard(Complaint complaint) {
        if (complaint.getLocation() == null || complaint.getMunicipality() == null) {
            return;
        }
        wardRepository
                .findByPointWithinBoundary(complaint.getMunicipality().getId(), complaint.getLocation())
                .ifPresent(complaint::assignWard);
    }

    // ------------------------------------------------------------------ hook 5: after

    /** Post-commit-adjacent work (SLA instances, moderation queues). */
    protected void afterSubmit(Complaint complaint, ComplaintSubmissionRequest request) {
        // Nothing in Phase 3. SLA instance creation lands with the scanner in Phase 5;
        // notifications are already covered by the outbox row recordSubmission writes.
    }

    // ------------------------------------------------------------------ variation points

    protected Priority initialPriority() {
        return Priority.NORMAL;
    }

    protected ModerationStatus initialModerationStatus() {
        return ModerationStatus.APPROVED;
    }

    protected String anonymousContactPhone(ComplaintSubmissionRequest request, User citizen) {
        return null;
    }

    /**
     * Which municipality owns this report.
     *
     * <p>Derived from the ward containing the point, so it works for a citizen with
     * no municipality membership — which is every citizen, since membership is an
     * authority concept. Falls back to the single active municipality in
     * single-tenant deployments.
     */
    protected Municipality resolveMunicipality(ComplaintSubmissionRequest request, User citizen, Point location) {
        Optional<Ward> containing = wardRepository.findByPointWithinBoundary(location);
        if (containing.isPresent()) {
            return containing.get().getMunicipality();
        }
        return municipalityRepository.findFirstByIsActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active municipality is configured"));
    }

    protected LocationSource resolveLocationSource(ComplaintSubmissionRequest request) {
        LocationSource declared = request.getLocationSource();
        return declared != null ? declared : LocationSource.DEVICE;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code NS-yyyy-######} from a database sequence.
     *
     * <p>A sequence rather than {@code count() + 1}: nextval is atomic and never
     * reuses a value, so two concurrent submissions cannot be handed the same
     * reference code. The number is what a citizen reads out over the phone, so it
     * has to be short, unique and non-guessable in bulk.
     */
    protected String nextReferenceCode() {
        Number sequence = (Number) entityManager
                .createNativeQuery("SELECT nextval('complaint_ref_seq')")
                .getSingleResult();
        int year = Year.from(clock.instant().atZone(ZoneOffset.UTC)).getValue();
        return "NS-%d-%06d".formatted(year, sequence.longValue());
    }

    protected Point toPoint(BigDecimal latitude, BigDecimal longitude) {
        // Longitude first: JTS coordinates are (x, y) and PostGIS geography follows.
        Point point = GEOMETRY_FACTORY.createPoint(
                new Coordinate(longitude.doubleValue(), latitude.doubleValue()));
        point.setSRID(4326);
        return point;
    }

    protected static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
