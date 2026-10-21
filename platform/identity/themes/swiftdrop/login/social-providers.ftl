<#macro show social>
  <div class="swiftdrop-social-divider devarom-social-divider">
      <span>or continue with</span>
  </div>
  <div id="kc-social-providers" class="swiftdrop-social-providers devarom-social-providers">
      <ul class="swiftdrop-social-list devarom-social-list">
          <#list social.providers as p>
              <li class="swiftdrop-social-item devarom-social-item">
                  <a data-once-link id="social-${p.alias}"
                     class="swiftdrop-social-btn devarom-social-btn swiftdrop-social- devarom-social-${p.alias}"
                     aria-label="${msg('signInWithProvider', p.displayName!)}"
                     type="button"
                     href="${p.loginUrl}">
                      <img src="${url.resourcesPath}/img/social/${p.providerId}.svg" aria-hidden="true" alt="" class="swiftdrop-social-icon devarom-social-icon" />
                      <span class="swiftdrop-social-text devarom-social-text">${msg("signInWithProvider", p.displayName!)}</span>
                  </a>
              </li>
          </#list>
      </ul>
  </div>
</#macro>
