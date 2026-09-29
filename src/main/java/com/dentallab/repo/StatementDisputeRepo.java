package com.dentallab.repo;

import com.dentallab.model.StatementDispute;
import com.dentallab.model.StatementDisputeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatementDisputeRepo extends JpaRepository<StatementDispute, Long> {
    List<StatementDispute> findByStatementIdOrderByIdAsc(Long statementId);

    long countByStatementId(Long statementId);

    List<StatementDispute> findByStatusOrderByIdDesc(StatementDisputeStatus status);
}
