package com.example.churnpoc.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Fast table wipes via TRUNCATE. At millions of rows this is near-instant, where a
 * DELETE would be slow and generate a large transaction.
 */
@Service
public class DataResetService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Empties both tables. FK checks are toggled off because prediction_result references
     * customer, and MySQL refuses to TRUNCATE a table referenced by a foreign key.
     */
    @Transactional
    public void wipeAll() {
        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
        entityManager.createNativeQuery("TRUNCATE TABLE prediction_result").executeUpdate();
        entityManager.createNativeQuery("TRUNCATE TABLE customer").executeUpdate();
        entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    /** Empties only prediction_result (nothing references it, so no FK toggle needed). */
    @Transactional
    public void wipePredictions() {
        entityManager.createNativeQuery("TRUNCATE TABLE prediction_result").executeUpdate();
    }
}
