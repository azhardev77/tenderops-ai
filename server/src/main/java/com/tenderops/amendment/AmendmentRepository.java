package com.tenderops.amendment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AmendmentRepository extends JpaRepository<Amendment, Long> {

  Optional<Amendment> findByNewVersionId(Long newVersionId);

  List<Amendment> findByTenderIdOrderByIdDesc(Long tenderId);
}
