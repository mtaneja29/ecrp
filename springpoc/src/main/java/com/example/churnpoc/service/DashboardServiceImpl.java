package com.example.churnpoc.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.churnpoc.dao.CustomerRepository;
import com.example.churnpoc.dao.PredictionResultRepository;
import com.example.churnpoc.dto.CustomerReview;
import com.example.churnpoc.dto.DashboardView;
import com.example.churnpoc.dto.RiskRow;
import com.example.churnpoc.entity.Customer;
import com.example.churnpoc.entity.PredictionResult;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final int PAGE_SIZE = 20;
    private static final Set<String> BANDS = Set.of("ALL", "HIGH", "MEDIUM", "LOW");

    private CustomerRepository customerRepository;
    private PredictionResultRepository predictionResultRepository;
    private DataResetService dataResetService;

    @Autowired
    public DashboardServiceImpl(CustomerRepository theCustomerRepository,
                                PredictionResultRepository thePredictionResultRepository,
                                DataResetService theDataResetService) {
        customerRepository = theCustomerRepository;
        predictionResultRepository = thePredictionResultRepository;
        dataResetService = theDataResetService;
    }

    @Override
    public DashboardView getDashboard(String band, int page) {
        if (band == null || !BANDS.contains(band)) {
            band = "HIGH";
        }
        if (page < 0) {
            page = 0;
        }

        long totalCustomers = customerRepository.count();
        long high = predictionResultRepository.countByRiskBand("HIGH");
        long highPending = predictionResultRepository.countByRiskBandAndActionStatus("HIGH", "PENDING");
        long medium = predictionResultRepository.countByRiskBand("MEDIUM");
        long mediumPending = predictionResultRepository.countByRiskBandAndActionStatus("MEDIUM", "PENDING");
        long low = predictionResultRepository.countByRiskBand("LOW");
        long lowPending = predictionResultRepository.countByRiskBandAndActionStatus("LOW", "PENDING");
        long totalPending = highPending + mediumPending + lowPending;
        boolean scored = (high + medium + low) > 0;

        Page<PredictionResult> resultPage = fetchPage(band, page);
        // past-the-end page (e.g. after data shrank): fall back to the last real page
        if (resultPage.isEmpty() && page > 0 && resultPage.getTotalPages() > 0) {
            page = resultPage.getTotalPages() - 1;
            resultPage = fetchPage(band, page);
        }

        // load customers for the visible page only, never the whole table
        List<Long> customerIds = resultPage.getContent().stream()
                .map(PredictionResult::getCustomerId)
                .toList();
        Map<Long, Customer> customersById = customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));

        List<RiskRow> rows = new ArrayList<>();
        for (PredictionResult result : resultPage.getContent()) {
            rows.add(toRow(result, customersById.get(result.getCustomerId())));
        }

        return new DashboardView(totalCustomers, totalPending, high, highPending, medium, mediumPending, low, lowPending, scored, rows,
                band, resultPage.getNumber(), resultPage.getTotalPages());
    }

    private Page<PredictionResult> fetchPage(String band, int page) {
        Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                Sort.by("churnProbability").descending());
        if ("ALL".equals(band)) {
            return predictionResultRepository.findAll(pageable);
        }
        return predictionResultRepository.findByRiskBand(band, pageable);
    }

    @Override
    public CustomerReview getReview(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("No customer with id " + customerId));
        PredictionResult prediction = predictionResultRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Customer " + customerId + " has not been scored yet"));

        String percentage = String.format("%.1f%%", prediction.getChurnProbability() * 100);
        return new CustomerReview(customer, prediction, percentage, reasonsFor(customer));
    }

    @Override
    @Transactional
    public void unflag(Long customerId) {
        predictionResultRepository.findByCustomerId(customerId)
                .ifPresent(r -> {
                    r.setActionStatus("UNFLAGGED");
                    predictionResultRepository.save(r);
                });
    }

    @Override
    public void clearAll() {
        dataResetService.wipeAll();
    }

    private RiskRow toRow(PredictionResult r, Customer c) {
        return new RiskRow(
                c.getId(),
                c.getExternalId(),
                c.getContract(),
                c.getTenure(),
                c.getMonthlyCharges(),
                String.format("%.1f%%", r.getChurnProbability() * 100),
                r.getRiskBand(),
                r.getActionStatus());
    }

    // each rule mirrors a factor the logistic regression weights toward churn
    private List<String> reasonsFor(Customer c) {
        List<String> reasons = new ArrayList<>();
        if ("Month-to-month".equals(c.getContract())) {
            reasons.add("Month-to-month contract - no commitment; the strongest churn driver in the model");
        }
        if (c.getTenure() != null && c.getTenure() <= 12) {
            reasons.add("New customer (" + c.getTenure() + " months) - churn risk is highest early in the relationship");
        }
        if ("Fiber optic".equals(c.getInternetService())) {
            reasons.add("Fiber optic internet - historically the highest-churn service tier in this data");
        }
        if ("Electronic check".equals(c.getPaymentMethod())) {
            reasons.add("Pays by electronic check - the payment method with the highest churn rate");
        }
        if (c.getMonthlyCharges() != null && c.getMonthlyCharges() >= 70) {
            reasons.add(String.format("High monthly charges (%.2f)", c.getMonthlyCharges()));
        }
        if ("No".equals(c.getTechSupport())) {
            reasons.add("No tech support add-on - support subscribers churn less");
        }
        if ("No".equals(c.getOnlineSecurity())) {
            reasons.add("No online security add-on - another retention-correlated service missing");
        }
        if (reasons.isEmpty()) {
            reasons.add("No single high-risk attribute stands out; the score comes from the combination of factors.");
        }
        return reasons;
    }
}
