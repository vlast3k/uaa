/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.authentication.manager;

import javax.servlet.http.HttpServletRequest;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudfoundry.identity.uaa.authentication.AccountNotVerifiedException;
import org.cloudfoundry.identity.uaa.authentication.AuthenticationPolicyRejectionException;
import org.cloudfoundry.identity.uaa.authentication.AuthzAuthenticationRequest;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthenticationDetails;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.authentication.event.IdentityProviderAuthenticationSuccessEvent;
import org.cloudfoundry.identity.uaa.authentication.event.IdentityProviderAuthenticationFailureEvent;
import org.cloudfoundry.identity.uaa.authentication.event.UnverifiedUserAuthenticationEvent;
import org.cloudfoundry.identity.uaa.authentication.event.UserAuthenticationFailureEvent;
import org.cloudfoundry.identity.uaa.authentication.event.UserNotFoundEvent;
import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.provider.IdentityProvider;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.PasswordPolicy;
import org.cloudfoundry.identity.uaa.provider.UaaIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.user.UaaAuthority;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.user.UaaUserDatabase;
import org.cloudfoundry.identity.uaa.user.UaaUserPrototype;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class AuthzAuthenticationManagerTests {
    private AuthzAuthenticationManager mgr;
    private UaaUserDatabase db;
    private ApplicationEventPublisher publisher;
    private static final String PASSWORD = "$2a$10$HoWPAUn9zqmmb0b.2TBZWe6cjQcxyo8TDwTX.5G46PBL347N3/0zO"; // "password"
    private UaaUser user = null;
    private BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private String loginServerUserName="loginServerUser".toLowerCase();
    private IdentityProviderProvisioning providerProvisioning;

    @Rule
    public final ExpectedException exception = ExpectedException.none();
    private ArgumentCaptor<ApplicationEvent> eventCaptor;

    @Before
    public void setUp() throws Exception {
        user = new UaaUser(getPrototype());
        providerProvisioning = mock(IdentityProviderProvisioning.class);
        db = mock(UaaUserDatabase.class);

        publisher = mock(ApplicationEventPublisher.class);
        eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        doNothing().when(publisher).publishEvent(eventCaptor.capture());
        AccountLoginPolicy mockAccountLoginPolicy = mock(AccountLoginPolicy.class);
        when(mockAccountLoginPolicy.isAllowed(any(), any())).thenReturn(true);

        mgr = new AuthzAuthenticationManager(db, encoder, providerProvisioning);
        mgr.setApplicationEventPublisher(publisher);
        mgr.setOrigin(OriginKeys.UAA);
        mgr.setAccountLoginPolicy(mockAccountLoginPolicy);
    }

    @After
    public void cleanUp() {
        IdentityZoneHolder.get().getConfig().getMfaConfig().setEnabled(false);
    }

    private UaaUserPrototype getPrototype() {
        String id = new RandomValueStringGenerator().generate();
        return new UaaUserPrototype()
            .withId(id)
            .withUsername("auser")
            .withPassword(PASSWORD)
            .withEmail("auser@blah.com")
            .withAuthorities(UaaAuthority.USER_AUTHORITIES)
            .withGivenName("A")
            .withFamilyName("User")
            .withOrigin(OriginKeys.UAA)
            .withZoneId(IdentityZoneHolder.get().getId())
            .withExternalId(id)
            .withPasswordLastModified(new Date(System.currentTimeMillis()))
            .withVerified(true);
    }

    @Test
    public void successfulAuthentication() {
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        Authentication result = mgr.authenticate(createAuthRequest("auser", "password"));
        assertNotNull(result);
        assertEquals("auser", result.getName());
        assertEquals("auser", ((UaaPrincipal) result.getPrincipal()).getName());
        assertThat(((UaaAuthentication)result).getAuthenticationMethods(), containsInAnyOrder("pwd"));

        List<ApplicationEvent> events = eventCaptor.getAllValues();
        assertThat(events.get(0), instanceOf(IdentityProviderAuthenticationSuccessEvent.class));
        assertEquals("auser", ((IdentityProviderAuthenticationSuccessEvent)events.get(0)).getUser().getUsername());
    }

    @Test
    public void unsuccessfulPasswordExpired() {
        IdentityProvider<UaaIdentityProviderDefinition> provider = new IdentityProvider<>();

        UaaIdentityProviderDefinition idpDefinition = new UaaIdentityProviderDefinition(new PasswordPolicy(6, 128, 1, 1, 1, 1, 6), null);
        provider.setConfig(idpDefinition);

        when(providerProvisioning.retrieveByOrigin(anyString(), anyString())).thenReturn(provider);

        Calendar oneYearAgoCal = Calendar.getInstance();
        oneYearAgoCal.add(Calendar.YEAR, -1);
        Date oneYearAgo = new Date(oneYearAgoCal.getTimeInMillis());
        user = new UaaUser(
            user.getId(),
            user.getUsername(),
            PASSWORD,
            user.getPassword(),
            user.getAuthorities(),
            user.getGivenName(),
            user.getFamilyName(),
            oneYearAgo,
            oneYearAgo,
            OriginKeys.UAA,
            null,
            true,
            IdentityZoneHolder.get().getId(),
            user.getSalt(),
            oneYearAgo);
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        Authentication authentication = mgr.authenticate(createAuthRequest("auser", "password"));
        assertTrue(((UaaAuthentication)authentication).isRequiresPasswordChange());
        assertTrue(authentication.isAuthenticated());
    }

    @Test(expected = BadCredentialsException.class)
    public void unsuccessfulLoginServerUserAuthentication() {
        when(db.retrieveUserByName(loginServerUserName, OriginKeys.UAA)).thenReturn(null);
        mgr.authenticate(createAuthRequest(loginServerUserName, ""));
        verify(db, times(0)).updateLastLogonTime(anyString());
    }

    @Test(expected = BadCredentialsException.class)
    public void unsuccessfulLoginServerUserWithPasswordAuthentication() {
        when(db.retrieveUserByName(loginServerUserName, OriginKeys.UAA)).thenReturn(null);
        mgr.authenticate(createAuthRequest(loginServerUserName, "dadas"));
    }

    @Test
    public void successfulAuthenticationReturnsTokenAndPublishesEvent() {
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        Authentication result = mgr.authenticate(createAuthRequest("auser", "password"));

        assertNotNull(result);
        assertEquals("auser", result.getName());
        assertEquals("auser", ((UaaPrincipal) result.getPrincipal()).getName());

        verify(publisher).publishEvent(isA(IdentityProviderAuthenticationSuccessEvent.class));
    }

    @Test
    public void invalidPasswordPublishesAuthenticationFailureEvent() {
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        try {
            mgr.authenticate(createAuthRequest("auser", "wrongpassword"));
            fail();
        } catch (BadCredentialsException expected) {
        }

        verify(publisher).publishEvent(isA(IdentityProviderAuthenticationFailureEvent.class));
        verify(publisher).publishEvent(isA(UserAuthenticationFailureEvent.class));
        verify(db, times(0)).updateLastLogonTime(anyString());
    }

    @Test(expected = AuthenticationPolicyRejectionException.class)
    public void authenticationIsDeniedIfRejectedByLoginPolicy() {
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        AccountLoginPolicy lp = mock(AccountLoginPolicy.class);
        when(lp.isAllowed(any(UaaUser.class), any(Authentication.class))).thenReturn(false);
        mgr.setAccountLoginPolicy(lp);
        mgr.authenticate(createAuthRequest("auser", "password"));
        verify(db, times(0)).updateLastLogonTime(anyString());
    }

    @Test
    public void missingUserPublishesNotFoundEvent() {
        when(db.retrieveUserByName(eq("aguess"),eq(OriginKeys.UAA))).thenThrow(new UsernameNotFoundException("mocked"));
        try {
            mgr.authenticate(createAuthRequest("aguess", "password"));
            fail();
        } catch (BadCredentialsException expected) {
        }

        verify(publisher).publishEvent(isA(UserNotFoundEvent.class));
    }

    @Test
    public void successfulVerifyOriginAuthentication1() {
        mgr.setOrigin("test");
        user = user.modifySource("test",null);
        when(db.retrieveUserByName("auser","test")).thenReturn(user);
        Authentication result = mgr.authenticate(createAuthRequest("auser", "password"));
        assertNotNull(result);
        assertEquals("auser", result.getName());
        assertEquals("auser", ((UaaPrincipal) result.getPrincipal()).getName());
    }

    @Test(expected = BadCredentialsException.class)
    public void originAuthenticationFail() {
        when(db.retrieveUserByName("auser", "not UAA")).thenReturn(user);
        mgr.authenticate(createAuthRequest("auser", "password"));
    }

    @Test
    public void unverifiedAuthenticationForOldUserSucceedsWhenAllowed() {
        mgr.setAllowUnverifiedUsers(true);
        user = new UaaUser(getPrototype().withLegacyVerificationBehavior(true));
        user.setVerified(false);
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        Authentication result = mgr.authenticate(createAuthRequest("auser", "password"));
        assertEquals("auser", result.getName());
        assertEquals("auser", ((UaaPrincipal) result.getPrincipal()).getName());
    }

    @Test
    public void unverifiedAuthenticationForNewUserFailsEvenWhenAllowed() {
        mgr.setAllowUnverifiedUsers(true);
        user.setVerified(false);
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        try {
            mgr.authenticate(createAuthRequest("auser", "password"));
            fail("Expected AccountNotVerifiedException");
        } catch(AccountNotVerifiedException e) {
            verify(publisher).publishEvent(isA(UnverifiedUserAuthenticationEvent.class));
        }
    }

    @Test
    public void authenticationWhenUserPasswordChangeRequired() {
        mgr.setAllowUnverifiedUsers(false);
        user.setPasswordChangeRequired(true);
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        Authentication authentication = mgr.authenticate(createAuthRequest("auser", "password"));
        assertTrue(((UaaAuthentication)authentication).isRequiresPasswordChange());
        assertTrue(authentication.isAuthenticated());
    }

    @Test
    public void unverifiedAuthenticationFailsWhenNotAllowed() {
        mgr.setAllowUnverifiedUsers(false);
        user.setVerified(false);
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        try {
            mgr.authenticate(createAuthRequest("auser", "password"));
            fail("Expected AccountNotVerifiedException");
        } catch(AccountNotVerifiedException e) {
            verify(publisher).publishEvent(isA(UnverifiedUserAuthenticationEvent.class));
        }
    }

    @Test
    public void testSystemWidePasswordExpiry() {
        IdentityProvider<UaaIdentityProviderDefinition> provider = new IdentityProvider<>();
        UaaIdentityProviderDefinition idpDefinition = mock(UaaIdentityProviderDefinition.class);
        provider.setConfig(idpDefinition);
        when(providerProvisioning.retrieveByOrigin(anyString(), anyString())).thenReturn(provider);
        PasswordPolicy policy = new PasswordPolicy();
        policy.setPasswordNewerThan(new Date(System.currentTimeMillis() + 1000));
        when(idpDefinition.getPasswordPolicy()).thenReturn(policy);
        when(db.retrieveUserByName("auser",OriginKeys.UAA)).thenReturn(user);
        Authentication authentication = mgr.authenticate(createAuthRequest("auser", "password"));
        assertTrue(((UaaAuthentication)authentication).isRequiresPasswordChange());
        assertTrue(authentication.isAuthenticated());
    }

    @Test
    public void testSystemWidePasswordExpiryWithPastDate() {
        IdentityProvider<UaaIdentityProviderDefinition> provider = new IdentityProvider<>();
        UaaIdentityProviderDefinition idpDefinition = mock(UaaIdentityProviderDefinition.class);
        provider.setConfig(idpDefinition);
        when(providerProvisioning.retrieveByOrigin(anyString(), anyString())).thenReturn(provider);
        PasswordPolicy policy = new PasswordPolicy();
        Date past = new Date(System.currentTimeMillis() - 10000000);
        policy.setPasswordNewerThan(past);
        when(idpDefinition.getPasswordPolicy()).thenReturn(policy);
        when(db.retrieveUserByName("auser",OriginKeys.UAA)).thenReturn(user);
        mgr.authenticate(createAuthRequest("auser", "password"));
    }

    @Test
    public void userIsLockedOutAfterNumberOfUnsuccessfulTriesIsExceeded() {
        AccountLoginPolicy lockoutPolicy = mock(PeriodLockoutPolicy.class);
        mgr.setAccountLoginPolicy(lockoutPolicy);
        when(db.retrieveUserByName("auser", OriginKeys.UAA)).thenReturn(user);
        Authentication authentication = createAuthRequest("auser", "password");
        when(lockoutPolicy.isAllowed(any(UaaUser.class), eq(authentication))).thenReturn(false);

        try {
            mgr.authenticate(authentication);
        } catch (AuthenticationPolicyRejectionException e) {
            // woo hoo
        }

        assertFalse(authentication.isAuthenticated());
        verify(publisher).publishEvent(isA(AuthenticationFailureLockedEvent.class));
    }

    AuthzAuthenticationRequest createAuthRequest(String username, String password) {
        Map<String, String> userdata = new HashMap<String, String>();
        userdata.put("username", username);
        userdata.put("password", password);
        return new AuthzAuthenticationRequest(userdata, new UaaAuthenticationDetails(mock(HttpServletRequest.class)));
    }
}