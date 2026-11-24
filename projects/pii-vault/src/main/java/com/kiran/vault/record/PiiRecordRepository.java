package com.kiran.vault.record;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PiiRecordRepository extends JpaRepository<PiiRecord, String> {

    List<PiiRecord> findBySsnIndex(String ssnIndex);

    List<PiiRecord> findByKekVersionNot(int kekVersion);
}
