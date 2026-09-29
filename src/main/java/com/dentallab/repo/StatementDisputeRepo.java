package com.dentallab.repo;

import com.dentallab.model.StatementDispute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatementDisputeRepo extends JpaRepository<StatementDispute, Long> {
    List<StatementDispute> findByStatementIdOrderByIdAsc(Long statementId);

    long countByStatementIdAndStatus(Long statementId, com.dentallab.model.DisputeStatus status);
}
