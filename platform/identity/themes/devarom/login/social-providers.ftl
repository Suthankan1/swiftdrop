<#macro show social>
  <div class="devarom-social-divider">
      <span>or continue with</span>
  </div>
  <div id="kc-social-providers" class="devarom-social-providers">
      <ul class="devarom-social-list">
          <#list social.providers as p>
              <li class="devarom-social-item">
                  <a data-once-link id="social-${p.alias}"
                     class="devarom-social-btn devarom-social-${p.alias}"
                     aria-label="${msg('signInWithProvider', p.displayName!)}"
                     type="button"
                     href="${p.loginUrl}">
                      <img src="${url.resourcesPath}/img/social/${p.providerId}.svg" aria-hidden="true" alt="" class="devarom-social-icon" />
                      <span class="devarom-social-text">${msg("signInWithProvider", p.displayName!)}</span>
                  </a>
              </li>
          </#list>
      </ul>
  </div>
</#macro>
