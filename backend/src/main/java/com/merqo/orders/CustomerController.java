package com.merqo.orders;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.merqo.access.AccessContext;
import com.merqo.access.Permission;
import com.merqo.common.error.ConflictException;
import com.merqo.common.error.ErrorCodes;
import com.merqo.common.error.ValidationException;
import com.merqo.location.Location;
import com.merqo.location.LocationRepository;
import com.merqo.orders.OrderDtos.CustomerHistoryResponse;
import com.merqo.orders.OrderDtos.CustomerOrderRefView;
import com.merqo.orders.PhoneRiskService.PhoneRiskSnapshot;
import com.merqo.orders.domain.PhoneNumber;
import com.merqo.user.CurrentUserService;

/** Authenticated customer lookup returning derived order history and refusal-risk tier. */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Derived customer phone history and risk indicators")
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final LocationRepository locationRepository;
    private final PhoneRiskService phoneRiskService;
    private final CurrentUserService currentUserService;

    public CustomerController(
            CustomerRepository customerRepository,
            LocationRepository locationRepository,
            PhoneRiskService phoneRiskService,
            CurrentUserService currentUserService) {
        this.customerRepository = customerRepository;
        this.locationRepository = locationRepository;
        this.phoneRiskService = phoneRiskService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/history")
    @Operation(summary = "Look up customer order history and refusal risk badge by phone number")
    public CustomerHistoryResponse history(@RequestParam("phone") String rawPhone) {
        AccessContext context = currentUserService.requireAccessContext();
        Location warehouse =
                locationRepository
                        .findFirstByOnlineFulfillmentTrueAndActiveTrue()
                        .orElseThrow(
                                () ->
                                        new ConflictException(
                                                ErrorCodes.ONLINE_LOCATION_NOT_SET,
                                                "No active warehouse is configured for online"
                                                        + " fulfillment."));
        OrderStaffService.requireWarehouseOrDeny(context, warehouse.getId(), Permission.ORDER_VIEW);

        PhoneNumber parsed = PhoneNumber.parse(rawPhone);
        if (!parsed.isValid()) {
            throw new ValidationException(
                    ErrorCodes.INVALID_PHONE,
                    "Phone must be a valid Bangladeshi mobile number (e.g. 01712345678).");
        }

        PhoneRiskSnapshot snapshot = phoneRiskService.evaluate(parsed.normalized(), null);
        String customerName =
                customerRepository
                        .findByNormalizedPhone(parsed.normalized())
                        .map(Customer::getName)
                        .orElseGet(
                                () ->
                                        snapshot.orders().isEmpty()
                                                ? null
                                                : snapshot.orders().getFirst().getCustomerName());

        List<CustomerOrderRefView> recentOrders =
                snapshot.orders().stream()
                        .limit(10)
                        .map(
                                o ->
                                        new CustomerOrderRefView(
                                                o.getId(),
                                                o.getOrderNumber(),
                                                o.getChannel(),
                                                o.getStatus(),
                                                o.getTotal(),
                                                o.getCreatedAt()))
                        .toList();

        return new CustomerHistoryResponse(
                parsed.raw(),
                parsed.normalized(),
                true,
                customerName,
                snapshot.history(),
                snapshot.riskTier(),
                recentOrders);
    }
}
