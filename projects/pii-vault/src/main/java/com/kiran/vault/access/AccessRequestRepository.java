package com.kiran.vault.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, String> {

    List<AccessRequest> findByStatusOrderByCreatedAtAsc(AccessRequest.Status status);
}
