package com.yuzugame.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface GameSessionRepository extends JpaRepository<GameSessionEntity, String> {

    @Query("SELECT COUNT(e) FROM GameSessionEntity e WHERE e.createdAt >= :since")
    long countSince(@Param("since") Instant since);

    @Query("SELECT e.currentChapter, COUNT(e) FROM GameSessionEntity e GROUP BY e.currentChapter ORDER BY COUNT(e) DESC")
    List<Object[]> countByChapter();

    @Query("SELECT AVG(CAST(e.turn AS DOUBLE)) FROM GameSessionEntity e")
    Double avgTurn();

    long countByEnded(boolean ended);
}
