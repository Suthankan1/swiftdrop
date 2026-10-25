package com.swiftdrop.deliveryservice.booking;
import java.util.List;
import java.net.URI;
import org.springframework.http.ProblemDetail;
public final class BookingFailure extends RuntimeException {
 public record InvalidParameter(String field, String message) {}
 private final int status;
 private final String code;
 private final List<InvalidParameter> invalidParameters;
 public BookingFailure(int status,String code,String safeDetail) { this(status,code,safeDetail,List.of()); }
 private BookingFailure(int status,String code,String detail,List<InvalidParameter> fields) {
  super(detail);this.status=status;this.code=code;this.invalidParameters=List.copyOf(fields);
 }
 public static BookingFailure validation(List<InvalidParameter> fields) {
  return new BookingFailure(400,"INVALID_REQUEST","Booking request validation failed.",fields);
 }
 public int status() { return status; }
 public String code() { return code; }
 public ProblemDetail problem() {
  var problem=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status),getMessage());
  problem.setType(URI.create("https://swiftdrop.com/errors/"+code));
  problem.setProperty("code",code);
  if(!invalidParameters.isEmpty()) problem.setProperty("invalidParameters",invalidParameters);
  return problem;
 }
}
