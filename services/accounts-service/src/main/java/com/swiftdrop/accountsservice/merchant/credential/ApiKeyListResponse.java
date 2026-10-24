package com.swiftdrop.accountsservice.merchant.credential;

import java.util.List;

public record ApiKeyListResponse(List<ApiKeySummaryItem> keys) {
}
