package com.swiftdrop.deliveryservice;

import com.swiftdrop.deliveryservice.booking.BookingService;
import com.swiftdrop.deliveryservice.assignment.controller.AssignmentController;
import com.swiftdrop.deliveryservice.proof.controller.ProofController;
import com.swiftdrop.deliveryservice.query.controller.ParcelQueryController;
import com.swiftdrop.deliveryservice.receipt.controller.ReceiptController;
import com.swiftdrop.deliveryservice.status.controller.StatusController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {ReceiptController.class, StatusController.class, AssignmentController.class, ParcelQueryController.class, ProofController.class}))
@Import(BookingFoundationSecurity.class)
class BookingExposureTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void productionBookingCommandIsNotExposed() throws Exception {
        mvc.perform(post("/api/v1/parcels").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noBookingServiceIsRegisteredWithoutApprovedAdapters() {
        assertThat(context.getBeansOfType(BookingService.class)).isEmpty();
    }
}
