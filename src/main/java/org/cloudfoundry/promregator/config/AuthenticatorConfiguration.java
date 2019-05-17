package org.cloudfoundry.promregator.config;

public class AuthenticatorConfiguration {
	private String type;
	
	private BasicAuthenticationConfiguration basic;

	private OAuth2XSUAAAuthenticationConfiguration oauth2xsuaa;
	
	private OAuth2BearerAuthenticationConfiguration oauth2bearer;
	
	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public BasicAuthenticationConfiguration getBasic() {
		return basic;
	}

	public void setBasic(BasicAuthenticationConfiguration basic) {
		this.basic = basic;
	}

	public OAuth2XSUAAAuthenticationConfiguration getOauth2xsuaa() {
		return oauth2xsuaa;
	}

	public void setOauth2xsuaa(OAuth2XSUAAAuthenticationConfiguration oauth2xsuaa) {
		this.oauth2xsuaa = oauth2xsuaa;
	}
	
	public OAuth2BearerAuthenticationConfiguration getOauth2bearer() {
		return oauth2bearer;
	}
	
	public void setOauth2bearer(OAuth2BearerAuthenticationConfiguration oauth2bearer) {
		this.oauth2bearer = oauth2bearer;
	}

}
