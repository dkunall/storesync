package com.storesync.shared.security;

import java.util.Optional;

/** Port implemented by the store module: staff and terminal subjects. */
public interface Principals {

    Optional<Principal> lookup(String subject);
}
