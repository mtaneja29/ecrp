package com.example.churnpoc.dao;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.churnpoc.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // keyset pagination for scanning millions of rows without OFFSET slowdown
    List<Customer> findByIdGreaterThanOrderByIdAsc(Long id, Limit limit);
}
