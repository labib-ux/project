package com.nagorikseba.security;

import com.nagorikseba.entity.User;
import com.nagorikseba.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = findUser(identifier);
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                .disabled(!user.isActive())
                .build();
    }

    public User findUser(String identifier) {
        String normalizedIdentifier = identifier.trim();
        if (normalizedIdentifier.startsWith("+880")) {
            normalizedIdentifier = "0" + normalizedIdentifier.substring(4);
        }
        String lookupIdentifier = normalizedIdentifier;
        return userRepository.findByEmailIgnoreCase(lookupIdentifier.toLowerCase(Locale.ROOT))
                .or(() -> userRepository.findByPhone(lookupIdentifier))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email/phone or password"));
    }
}
