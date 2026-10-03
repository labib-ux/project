package com.nagorikseba.notification;

import com.nagorikseba.complaint.domain.Complaint;
import com.nagorikseba.enums.UserRole;
import com.nagorikseba.identity.domain.User;
import com.nagorikseba.identity.repo.MembershipRepository;
import com.nagorikseba.identity.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who must hear about a brand-new complaint (§7.4).
 *
 * <p>A submission has no assignee yet, so the assigned-officer path in
 * {@link NotificationDispatcher} can never fire for it — without this resolver a
 * filed report would reach the citizen and nobody in the authority, which is
 * exactly the failure the demo cannot have. Three groups qualify:
 *
 * <ul>
 *   <li>the filing citizen (handled by the dispatcher, listed here for completeness),</li>
 *   <li>every active officer/councillor posted to the complaint's municipality,</li>
 *   <li>every active admin, who are cross-tenant for oversight (§8.2).</li>
 * </ul>
 *
 * <p>Membership, not {@code users.department_id}, decides who serves where: §3.2
 * models transfers as membership history so {@code user_municipality_memberships}
 * is the authoritative tenancy record, and a stale FK would page the wrong desk.
 */
@Component
@RequiredArgsConstructor
public class NotificationRecipientResolver {

    /** Roles that receive an unassigned report. Citizens are excluded by construction. */
    private static final Set<UserRole> AUTHORITY_ROLES =
            EnumSet.of(UserRole.DEPT_OFFICER, UserRole.WARD_COUNCILOR);

    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    /**
     * Recipients for a newly submitted complaint, de-duplicated and order-stable.
     *
     * <p>Read-only and cheap: two indexed queries, no entity writes. Returns ids
     * rather than users so the caller never holds a lazy association it might
     * touch after the transaction closes.
     */
    @Transactional(readOnly = true)
    public List<Long> authorityRecipientsFor(Complaint complaint) {
        Set<Long> recipients = new LinkedHashSet<>();

        if (complaint.getMunicipality() != null) {
            membershipRepository.findActiveStaffServingMunicipality(
                            complaint.getMunicipality().getId(), AUTHORITY_ROLES)
                    .stream()
                    .map(User::getId)
                    .forEach(recipients::add);
        }

        userRepository.findByRoleAndActiveTrue(UserRole.ADMIN)
                .stream()
                .map(User::getId)
                .forEach(recipients::add);

        // A complaint filed by someone who also holds staff rights must not page them
        // about their own report.
        if (complaint.getCitizen() != null) {
            recipients.remove(complaint.getCitizen().getId());
        }
        return List.copyOf(recipients);
    }
}