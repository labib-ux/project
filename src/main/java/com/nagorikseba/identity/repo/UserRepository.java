package com.nagorikseba.identity.repo;

import com.nagorikseba.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Blueprint I4 — user lookups for the login path.
 *
 * <p>Email comparisons go through {@code lower(...)} even though the column is
 * CITEXT: the JDBC driver binds parameters as {@code varchar}, and a
 * {@code citext = varchar} comparison resolves to {@code text = text}, which is
 * case-sensitive. Lowering both sides keeps behaviour identical regardless of
 * driver typing, while the CITEXT unique index still blocks mixed-case
 * duplicates at the database level.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByPhone(String phone);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhone(String phone);

    /** Single-round-trip login lookup: the identifier may be an email or a canonical phone. */
    @Query("""
            select u from User u
            where lower(u.email) = lower(:identifier)
               or u.phone = :identifier
            """)
    Optional<User> findByIdentifier(@Param("identifier") String identifier);
}
