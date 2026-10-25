package com.swiftdrop.deliveryservice.booking;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
/** Only construct at the trusted application boundary after resource-server JWT validation. */
public record BookingActor(String subject) {
 public BookingActor {
  if (subject==null || subject.isBlank() || subject.length()>255)
   throw new BookingFailure(401,"UNAUTHENTICATED","A valid authenticated subject is required.");
 }
 public static BookingActor from(JwtAuthenticationToken authentication) {
  if (authentication==null || !authentication.isAuthenticated())
   throw new BookingFailure(401,"UNAUTHENTICATED","Authentication required.");
  if(authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER")))
   throw new BookingFailure(403,"FORBIDDEN","Customer access required.");
  return new BookingActor(authentication.getToken().getSubject());
 }
}
