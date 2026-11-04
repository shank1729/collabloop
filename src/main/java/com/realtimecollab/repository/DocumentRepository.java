package com.realtimecollab.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.realtimecollab.entity.Document;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    @Query("""
            select count(d) > 0 from Document d
            where d.id = :documentId and (d.owner.email = :email or exists (
                select a.id from DocumentAccess a where a.document = d and a.user.email = :email
            ))
            """)
    boolean canRead(@Param("documentId") Long documentId, @Param("email") String email);

    @Query("""
            select distinct d
            from Document d
            left join fetch d.owner
            left join fetch d.lastEditedBy
            left join fetch d.collaboratorAccesses access
            left join fetch access.user
            where d.owner.id = :userId or exists (
                select a.id from DocumentAccess a where a.document = d and a.user.id = :userId
            )
            """)
    List<Document> findAllAccessibleByUserId(@Param("userId") Long userId);

    @Query("""
            select distinct d
            from Document d
            left join fetch d.owner
            left join fetch d.lastEditedBy
            left join fetch d.collaboratorAccesses access
            left join fetch access.user
            where d.id = :documentId and (d.owner.id = :userId or exists (
                select a.id from DocumentAccess a where a.document = d and a.user.id = :userId
            ))
            """)
    Optional<Document> findAccessibleByIdAndUserId(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId
    );
}
