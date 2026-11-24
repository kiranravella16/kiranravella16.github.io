package com.kiran.risk.web;

import com.kiran.risk.bureau.SimulatedBureauGateway;
import com.kiran.risk.decision.DecisionService;
import com.kiran.risk.model.Models.Decision;
import com.kiran.risk.model.Models.LoanApplication;
import com.kiran.risk.model.Models.Product;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class DecisionController {

    private final DecisionService service;
    private final SimulatedBureauGateway simulations;

    public DecisionController(DecisionService service, SimulatedBureauGateway simulations) {
        this.service = service;
        this.simulations = simulations;
    }

    public record DecisionRequest(
            @NotBlank String applicationId,
            @NotBlank String customerId,
            @NotNull Product product,
            @NotNull @DecimalMin("50.00") @DecimalMax("25000.00") BigDecimal amount,
            @Min(1) @Max(24) Integer termMonths,
            @NotNull @DecimalMin("0.00") BigDecimal monthlyIncome,
            @Pattern(regexp = "^[A-Z]{2}$") String state) {

        LoanApplication toDomain() {
            return new LoanApplication(applicationId, customerId, product, amount,
                    product == Product.PAY_IN_4 ? 2 : (termMonths == null ? 12 : termMonths), monthlyIncome, state);
        }
    }

    /**
     * @param simulate {@code X-Simulate: true} replays recorded bureau responses instead of live pulls
     */
    @PostMapping("/decisions")
    public Decision decide(@Valid @RequestBody DecisionRequest request,
                           @RequestHeader(value = "X-Simulate", defaultValue = "false") boolean simulate) {
        return service.decide(request.toDomain(), simulate);
    }

    @GetMapping("/decisions/{id}")
    public Decision get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/simulations")
    public Map<String, String> simulations() {
        return simulations.scenarios().values().stream()
                .collect(Collectors.toMap(SimulatedBureauGateway.Scenario::customerId,
                        SimulatedBureauGateway.Scenario::description));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
