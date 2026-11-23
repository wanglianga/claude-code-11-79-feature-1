package com.ctrial.repo;

import com.ctrial.model.ConsentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsentTemplateRepo extends JpaRepository<ConsentTemplate, Long> {
    Optional<ConsentTemplate> findByCode(String code);

    List<ConsentTemplate> findAllByOrderBySiteCodeAscPublishedFromAsc();

    List<ConsentTemplate> findBySiteCodeOrderByPublishedFromAsc(String siteCode);
}
