<%-- ZenithCore look and feel (Pollux Solutions Ltd). Included last by theme.css.dsp, so it overrides
     the iceblue_c-derived defaults. Brand colours from the ZenithCore logo:
     navy #00387F (wordmark), blue #0088C0, cyan #00B8F0, light tint #E3F4FB. --%>
:root {
	--zk-color-primary: #00387F;
	--zk-toolbar-button-checked-background-color: #00B8F0;
	--zk-appmenu-link-hover-background-color: #E3F4FB;
	--zk-borderlayout-collapsed-background-color: #E3F4FB;
	--zk-button-os-hover-background-color: #0088C0;
	--zk-desktop-header-background-color: #FFFFFF;
	--zk-desktop-header-border-color: #00B8F0;
	--zk-desktop-header-hover-background-color: #E3F4FB;
	--zk-field-editor-action-hover-background-color: #0088C0;
	--zk-gadget-recent-item-hover-background-color: #E3F4FB;
	--zk-grid-header-background-color: #E3F4FB;
	--zk-grid-sort-active-background-color: #C9EBF8;
	--zk-input-element-hover-background-color: #0088C0;
	--zk-login-window-background-color: #00387F;
	--zk-login-header-text-color: #00387F;
	--zk-window-header-color: #00387F;
}

<%-- Login: brand gradient behind the login card --%>
.login-window, .login-window .z-window-content {
	background-image: linear-gradient(155deg, #00387F 0%, #0067A8 55%, #00B8F0 100%);
}
.login-east-panel, .login-west-panel { border-right: 3px solid #00B8F0; }
.zc-vendor-name { color: #00387F; font-size: 1.3em; font-weight: 700; }
.zc-vendor-by { color: #0088C0; font-size: 0.95em; }

<%-- Desktop header: white with a cyan rule --%>
.desktop-header { border-bottom: 3px solid #00B8F0; }

<%-- Secondary buttons outlined in navy --%>
.btn-cancel.z-button { border: 1px solid #00387F; color: #00387F; background-color: #FFFFFF; }
