package com.realtimecollab.repository;

import com.realtimecollab.entity.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {
    @Query("""
            select v from DocumentVersion v
            left join fetch v.editedBy
            join fetch v.document
            where v.document.id = :documentId
            order by v.editedAt desc, v.id desc
            """)
    List<DocumentVersion> findByDocumentIdOrderByEditedAtDesc(@Param("documentId") Long documentId);

    @Transactional
    @Modifying
    @Query("delete from DocumentVersion dv where dv.document.id = :documentId")
    void deleteByDocumentId(Long documentId);
}
