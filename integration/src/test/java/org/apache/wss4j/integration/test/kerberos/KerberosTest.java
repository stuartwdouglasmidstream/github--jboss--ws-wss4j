/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.wss4j.integration.test.kerberos;

import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.kerberos.KerberosContextAndServiceNameCallback;
import org.apache.wss4j.common.spnego.SpnegoTokenContext;
import org.apache.wss4j.common.util.XMLUtils;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSSConfig;
import org.apache.wss4j.dom.WSSecurityEngine;
import org.apache.wss4j.dom.WSSecurityEngineResult;
import org.apache.wss4j.dom.common.SecurityTestUtil;
import org.apache.wss4j.dom.message.WSSecEncrypt;
import org.apache.wss4j.dom.message.WSSecHeader;
import org.apache.wss4j.dom.message.WSSecSignature;
import org.apache.wss4j.dom.message.token.BinarySecurity;
import org.apache.wss4j.dom.message.token.KerberosSecurity;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.apache.wss4j.dom.validate.KerberosTokenValidator;
import org.apache.wss4j.integration.test.common.KerberosServiceStarter;
import org.apache.wss4j.stax.WSSec;
import org.apache.wss4j.stax.ext.*;
import org.apache.wss4j.stax.securityEvent.KerberosTokenSecurityEvent;
import org.apache.wss4j.stax.test.AbstractTestBase;
import org.apache.wss4j.stax.test.utils.SOAPUtil;
import org.apache.wss4j.stax.test.utils.StAX2DOM;
import org.apache.wss4j.stax.test.utils.XmlReaderToWriter;
import org.apache.xml.security.exceptions.XMLSecurityException;
import org.apache.xml.security.stax.securityEvent.SecurityEvent;
import org.apache.xml.security.stax.securityEvent.SecurityEventListener;
import org.apache.xml.security.utils.Base64;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.crypto.SecretKey;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

@org.junit.Ignore
public class KerberosTest extends AbstractTestBase {
    
    private static final org.slf4j.Logger LOG = 
        org.slf4j.LoggerFactory.getLogger(KerberosTest.class);

    private static boolean kerberosServerStarted = false;

    @BeforeClass
    public static void setUp() throws Exception {

        WSSConfig.init();

        //
        // This test fails with the IBM JDK
        //
        if (!"IBM Corporation".equals(System.getProperty("java.vendor"))) {
            kerberosServerStarted = KerberosServiceStarter.startKerberosServer();

            String basedir = System.getProperty("basedir");
            if (basedir == null) {
                basedir = new File(".").getCanonicalPath();
            } else {
                basedir += "/..";
            }

            //System.setProperty("sun.security.krb5.debug", "true");
            System.setProperty("java.security.auth.login.config", basedir + "/integration/src/test/resources/kerberos/kerberos.jaas");
            System.setProperty("java.security.krb5.conf", basedir + "/integration/src/test/resources/kerberos/krb5.conf");
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (kerberosServerStarted) {
            KerberosServiceStarter.stopKerberosServer();
        }
        SecurityTestUtil.cleanup();
    }
    
    //
    // DOM tests
    //
    
    /**
     * Test using the KerberosSecurity class to retrieve a service ticket from a KDC, wrap it
     * in a BinarySecurityToken, and process it.
     */
    @Test
    public void testKerberosCreationAndProcessing() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        
        KerberosSecurity bst = new KerberosSecurity(doc);
        CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                PasswordCallback passwordCallback = (PasswordCallback)callbacks[0];
                if (passwordCallback.getPrompt().contains("alice")) {
                    passwordCallback.setPassword("alice".toCharArray());
                } else if (passwordCallback.getPrompt().contains("bob")) {
                    passwordCallback.setPassword("bob".toCharArray());
                }
            }
        };
        bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
        WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                XMLUtils.PrettyDocumentToString(doc);
            LOG.debug(outputString);
        }
        
        // Configure the Validator
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        KerberosTokenValidator validator = new KerberosTokenValidator();
        validator.setContextName("bob");
        validator.setServiceName("bob@service.ws.apache.org");
        wssConfig.setValidator(WSSecurityEngine.BINARY_TOKEN, validator);
        WSSecurityEngine secEngine = new WSSecurityEngine();
        secEngine.setWssConfig(wssConfig);

        List<WSSecurityEngineResult> results =
            secEngine.processSecurityHeader(doc, null, callbackHandler, null);
        WSSecurityEngineResult actionResult =
            WSSecurityUtil.fetchActionResult(results, WSConstants.BST);
        BinarySecurity token =
            (BinarySecurity)actionResult.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
        Assert.assertTrue(token != null);
        
        Principal principal = (Principal)actionResult.get(WSSecurityEngineResult.TAG_PRINCIPAL);
        Assert.assertTrue(principal instanceof KerberosPrincipal);
        Assert.assertTrue(principal.getName().contains("alice"));
    }
    
    /**
     * Get and validate a SPNEGO token.
     */
    @Test
    public void testSpnego() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        
        SpnegoTokenContext spnegoToken = new SpnegoTokenContext();
        CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                PasswordCallback passwordCallback = (PasswordCallback)callbacks[0];
                if (passwordCallback.getPrompt().contains("alice")) {
                    passwordCallback.setPassword("alice".toCharArray());
                } else if (passwordCallback.getPrompt().contains("bob")) {
                    passwordCallback.setPassword("bob".toCharArray());
                }
            }
        };
        spnegoToken.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
        
        byte[] token = spnegoToken.getToken();
        Assert.assertNotNull(token);
        
        spnegoToken = new SpnegoTokenContext();
        spnegoToken.validateServiceTicket("bob", callbackHandler, "bob@service.ws.apache.org", token);
        Assert.assertTrue(spnegoToken.isEstablished());
    }
    
    /**
     * Various unit tests for a kerberos client
     */
    @Test
    public void testKerberosClient() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

        CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                PasswordCallback passwordCallback = (PasswordCallback)callbacks[0];
                if (passwordCallback.getPrompt().contains("alice")) {
                    passwordCallback.setPassword("alice".toCharArray());
                } else if (passwordCallback.getPrompt().contains("bob")) {
                    passwordCallback.setPassword("bob".toCharArray());
                }
            }
        };

        try {
            KerberosSecurity bst = new KerberosSecurity(doc);
            bst.retrieveServiceTicket("alice2", callbackHandler, "bob@service");
            Assert.fail("Failure expected on an unknown user");
        } catch (WSSecurityException ex) {
            Assert.assertEquals(ex.getMessage(), "An error occurred in trying to obtain a TGT: No LoginModules configured for alice2");
        }
        
        
        try {
            KerberosSecurity bst = new KerberosSecurity(doc);
            bst.retrieveServiceTicket("alice", callbackHandler, "bob2@service");
            Assert.fail("Failure expected on an unknown user");
        } catch (WSSecurityException ex) {
            Assert.assertEquals(ex.getMessage(), "An error occurred in trying to obtain a service ticket");
        }
        
    }
    
    /**
     * Test using the KerberosSecurity class to retrieve a service ticket from a KDC, wrap it
     * in a BinarySecurityToken, and use the session key to sign the SOAP Body.
     */
    @Test
    public void testKerberosSignature() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        
        KerberosSecurity bst = new KerberosSecurity(doc);
        CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                if (callbacks[0] instanceof PasswordCallback) {
                    PasswordCallback passwordCallback = (PasswordCallback)callbacks[0];
                    if (passwordCallback.getPrompt().contains("alice")) {
                        passwordCallback.setPassword("alice".toCharArray());
                    } else if (passwordCallback.getPrompt().contains("bob")) {
                        passwordCallback.setPassword("bob".toCharArray());
                    }
                }
            }
        };
        bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
        bst.setID("Id-" + bst.hashCode());
        WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());
        
        WSSecSignature sign = new WSSecSignature();
        sign.setSignatureAlgorithm(SignatureMethod.HMAC_SHA1);
        sign.setKeyIdentifierType(WSConstants.CUSTOM_SYMM_SIGNING);
        sign.setCustomTokenId(bst.getID());
        sign.setCustomTokenValueType(WSConstants.WSS_GSS_KRB_V5_AP_REQ);
        
        SecretKey secretKey = bst.getSecretKey();
        sign.setSecretKey(secretKey.getEncoded());
        
        Document signedDoc = sign.build(doc, null, secHeader);
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                XMLUtils.PrettyDocumentToString(signedDoc);
            LOG.debug(outputString);
        }
        
        // Configure the Validator
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        KerberosTokenValidator validator = new KerberosTokenValidator();
        validator.setContextName("bob");
        validator.setServiceName("bob@service.ws.apache.org");
        wssConfig.setValidator(WSSecurityEngine.BINARY_TOKEN, validator);
        WSSecurityEngine secEngine = new WSSecurityEngine();
        secEngine.setWssConfig(wssConfig);
        
        List<WSSecurityEngineResult> results = 
            secEngine.processSecurityHeader(doc, null, callbackHandler, null);
        WSSecurityEngineResult actionResult =
            WSSecurityUtil.fetchActionResult(results, WSConstants.BST);
        BinarySecurity token =
            (BinarySecurity)actionResult.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
        Assert.assertTrue(token != null);
        
        Principal principal = (Principal)actionResult.get(WSSecurityEngineResult.TAG_PRINCIPAL);
        Assert.assertTrue(principal instanceof KerberosPrincipal);
        Assert.assertTrue(principal.getName().contains("alice"));
    }
    
    
    /**
     * Test using the KerberosSecurity class to retrieve a service ticket from a KDC, wrap it
     * in a BinarySecurityToken, and use the session key to sign the SOAP Body.
     */
    @Test
    public void testKerberosSignatureKI() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        
        KerberosSecurity bst = new KerberosSecurity(doc);
        CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                if (callbacks[0] instanceof PasswordCallback) {
                    PasswordCallback passwordCallback = (PasswordCallback)callbacks[0];
                    if (passwordCallback.getPrompt().contains("alice")) {
                        passwordCallback.setPassword("alice".toCharArray());
                    } else if (passwordCallback.getPrompt().contains("bob")) {
                        passwordCallback.setPassword("bob".toCharArray());
                    }
                }
            }
        };
        bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
        bst.setID("Id-" + bst.hashCode());
        
        WSSecSignature sign = new WSSecSignature();
        sign.setSignatureAlgorithm(SignatureMethod.HMAC_SHA1);
        sign.setKeyIdentifierType(WSConstants.CUSTOM_KEY_IDENTIFIER);
        sign.setCustomTokenValueType(WSConstants.WSS_KRB_KI_VALUE_TYPE);
        
        SecretKey secretKey = bst.getSecretKey();
        byte[] keyData = secretKey.getEncoded();
        sign.setSecretKey(keyData);
        
        byte[] digestBytes = WSSecurityUtil.generateDigest(bst.getToken());
        sign.setCustomTokenId(Base64.encode(digestBytes));
        
        Document signedDoc = sign.build(doc, null, secHeader);
        
        WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                XMLUtils.PrettyDocumentToString(signedDoc);
            LOG.debug(outputString);
        }
        
        // Configure the Validator
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        KerberosTokenValidator validator = new KerberosTokenValidator();
        validator.setContextName("bob");
        validator.setServiceName("bob@service.ws.apache.org");
        wssConfig.setValidator(WSSecurityEngine.BINARY_TOKEN, validator);
        WSSecurityEngine secEngine = new WSSecurityEngine();
        secEngine.setWssConfig(wssConfig);
        
        List<WSSecurityEngineResult> results = 
            secEngine.processSecurityHeader(doc, null, callbackHandler, null);
        WSSecurityEngineResult actionResult =
            WSSecurityUtil.fetchActionResult(results, WSConstants.BST);
        BinarySecurity token =
            (BinarySecurity)actionResult.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
        Assert.assertTrue(token != null);
        
        Principal principal = (Principal)actionResult.get(WSSecurityEngineResult.TAG_PRINCIPAL);
        Assert.assertTrue(principal instanceof KerberosPrincipal);
        Assert.assertTrue(principal.getName().contains("alice"));
    }
    
    
    /**
     * Test using the KerberosSecurity class to retrieve a service ticket from a KDC, wrap it
     * in a BinarySecurityToken, and use the session key to encrypt the SOAP Body.
     */
    @Test
    public void testKerberosEncryption() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        
        KerberosSecurity bst = new KerberosSecurity(doc);
        CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                if (callbacks[0] instanceof PasswordCallback) {
                    PasswordCallback passwordCallback = (PasswordCallback)callbacks[0];
                    if (passwordCallback.getPrompt().contains("alice")) {
                        passwordCallback.setPassword("alice".toCharArray());
                    } else if (passwordCallback.getPrompt().contains("bob")) {
                        passwordCallback.setPassword("bob".toCharArray());
                    }
                }
            }
        };
        bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
        bst.setID("Id-" + bst.hashCode());
        WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());
        
        WSSecEncrypt builder = new WSSecEncrypt();
        builder.setSymmetricEncAlgorithm(WSConstants.AES_128);
        SecretKey secretKey = bst.getSecretKey();
        builder.setSymmetricKey(secretKey);
        builder.setEncryptSymmKey(false);
        builder.setCustomReferenceValue(WSConstants.WSS_GSS_KRB_V5_AP_REQ);
        builder.setEncKeyId(bst.getID());

        Document encryptedDoc = builder.build(doc, null, secHeader);
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                XMLUtils.PrettyDocumentToString(encryptedDoc);
            LOG.debug(outputString);
        }
        
        // Configure the Validator
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        KerberosTokenValidator validator = new KerberosTokenValidator();
        validator.setContextName("bob");
        validator.setServiceName("bob@service.ws.apache.org");
        wssConfig.setValidator(WSSecurityEngine.BINARY_TOKEN, validator);
        WSSecurityEngine secEngine = new WSSecurityEngine();
        secEngine.setWssConfig(wssConfig);
        
        List<WSSecurityEngineResult> results = 
            secEngine.processSecurityHeader(encryptedDoc, null, callbackHandler, null);
        WSSecurityEngineResult actionResult =
            WSSecurityUtil.fetchActionResult(results, WSConstants.BST);
        BinarySecurity token =
            (BinarySecurity)actionResult.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
        Assert.assertTrue(token != null);
        
        Principal principal = (Principal)actionResult.get(WSSecurityEngineResult.TAG_PRINCIPAL);
        Assert.assertTrue(principal instanceof KerberosPrincipal);
        Assert.assertTrue(principal.getName().contains("alice"));
    }
    
    /**
     * Test using the KerberosSecurity class to retrieve a service ticket from a KDC, wrap it
     * in a BinarySecurityToken, and use the session key to encrypt the SOAP Body.
     */
    @Test
    public void testKerberosEncryptionBSTFirst() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        
        KerberosSecurity bst = new KerberosSecurity(doc);
        CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                if (callbacks[0] instanceof PasswordCallback) {
                    PasswordCallback passwordCallback = (PasswordCallback)callbacks[0];
                    if (passwordCallback.getPrompt().contains("alice")) {
                        passwordCallback.setPassword("alice".toCharArray());
                    } else if (passwordCallback.getPrompt().contains("bob")) {
                        passwordCallback.setPassword("bob".toCharArray());
                    }
                }
            }
        };
        bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
        bst.setID("Id-" + bst.hashCode());
        
        WSSecEncrypt builder = new WSSecEncrypt();
        builder.setSymmetricEncAlgorithm(WSConstants.AES_128);
        SecretKey secretKey = bst.getSecretKey();
        builder.setSymmetricKey(secretKey);
        builder.setEncryptSymmKey(false);
        builder.setCustomReferenceValue(WSConstants.WSS_GSS_KRB_V5_AP_REQ);
        builder.setEncKeyId(bst.getID());

        Document encryptedDoc = builder.build(doc, null, secHeader);
        
        WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                XMLUtils.PrettyDocumentToString(encryptedDoc);
            LOG.debug(outputString);
        }
        
        // Configure the Validator
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        KerberosTokenValidator validator = new KerberosTokenValidator();
        validator.setContextName("bob");
        validator.setServiceName("bob@service.ws.apache.org");
        wssConfig.setValidator(WSSecurityEngine.BINARY_TOKEN, validator);
        WSSecurityEngine secEngine = new WSSecurityEngine();
        secEngine.setWssConfig(wssConfig);
        
        List<WSSecurityEngineResult> results = 
            secEngine.processSecurityHeader(encryptedDoc, null, callbackHandler, null);
        WSSecurityEngineResult actionResult =
            WSSecurityUtil.fetchActionResult(results, WSConstants.BST);
        BinarySecurity token =
            (BinarySecurity)actionResult.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
        Assert.assertTrue(token != null);
        
        Principal principal = (Principal)actionResult.get(WSSecurityEngineResult.TAG_PRINCIPAL);
        Assert.assertTrue(principal instanceof KerberosPrincipal);
        Assert.assertTrue(principal.getName().contains("alice"));
    }
    
    /**
     * Test using the KerberosSecurity class to retrieve a service ticket from a KDC, wrap it
     * in a BinarySecurityToken, and use the session key to encrypt the SOAP Body.
     */
    @Test
    public void testKerberosEncryptionKI() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

        WSSecHeader secHeader = new WSSecHeader();
        secHeader.insertSecurityHeader(doc);
        
        KerberosSecurity bst = new KerberosSecurity(doc);
        CallbackHandler callbackHandler = new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                if (callbacks[0] instanceof PasswordCallback) {
                    PasswordCallback passwordCallback = (PasswordCallback)callbacks[0];
                    if (passwordCallback.getPrompt().contains("alice")) {
                        passwordCallback.setPassword("alice".toCharArray());
                    } else if (passwordCallback.getPrompt().contains("bob")) {
                        passwordCallback.setPassword("bob".toCharArray());
                    }
                }
            }
        };
        bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
        bst.setID("Id-" + bst.hashCode());
        
        WSSecEncrypt builder = new WSSecEncrypt();
        builder.setSymmetricEncAlgorithm(WSConstants.AES_128);
        SecretKey secretKey = bst.getSecretKey();
        builder.setSymmetricKey(secretKey);
        builder.setEncryptSymmKey(false);
        builder.setCustomReferenceValue(WSConstants.WSS_KRB_KI_VALUE_TYPE);

        byte[] digestBytes = WSSecurityUtil.generateDigest(bst.getToken());
        builder.setEncKeyId(Base64.encode(digestBytes));
        
        Document encryptedDoc = builder.build(doc, null, secHeader);
        
        WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());
        
        if (LOG.isDebugEnabled()) {
            String outputString = 
                XMLUtils.PrettyDocumentToString(encryptedDoc);
            LOG.debug(outputString);
        }
        
        // Configure the Validator
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        KerberosTokenValidator validator = new KerberosTokenValidator();
        validator.setContextName("bob");
        validator.setServiceName("bob@service.ws.apache.org");
        wssConfig.setValidator(WSSecurityEngine.BINARY_TOKEN, validator);
        WSSecurityEngine secEngine = new WSSecurityEngine();
        secEngine.setWssConfig(wssConfig);
        
        List<WSSecurityEngineResult> results = 
            secEngine.processSecurityHeader(encryptedDoc, null, callbackHandler, null);
        WSSecurityEngineResult actionResult =
            WSSecurityUtil.fetchActionResult(results, WSConstants.BST);
        BinarySecurity token =
            (BinarySecurity)actionResult.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
        Assert.assertTrue(token != null);
        
        Principal principal = (Principal)actionResult.get(WSSecurityEngineResult.TAG_PRINCIPAL);
        Assert.assertTrue(principal instanceof KerberosPrincipal);
        Assert.assertTrue(principal.getName().contains("alice"));
    }
    
    //
    // Streaming tests
    //

    @Test
    public void testKerberosSignatureOutbound() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document document;
        {
            WSSSecurityProperties securityProperties = new WSSSecurityProperties();
            List<WSSConstants.Action> actions = new ArrayList<WSSConstants.Action>();
            actions.add(WSSConstants.SIGNATURE_WITH_KERBEROS_TOKEN);
            securityProperties.setActions(actions);
            securityProperties.setCallbackHandler(new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof KerberosContextAndServiceNameCallback) {
                        KerberosContextAndServiceNameCallback kerberosContextAndServiceNameCallback =
                                (KerberosContextAndServiceNameCallback) callbacks[0];
                        kerberosContextAndServiceNameCallback.setContextName("alice");
                        kerberosContextAndServiceNameCallback.setServiceName("bob@service.ws.apache.org");
                    } else if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("alice")) {
                            passwordCallback.setPassword("alice".toCharArray());
                        }
                    }
                }
            });

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            OutboundWSSec wsSecOut = WSSec.getOutboundWSSec(securityProperties);
            XMLStreamWriter xmlStreamWriter = wsSecOut.processOutMessage(baos, "UTF-8", new ArrayList<SecurityEvent>());
            XMLStreamReader xmlStreamReader = xmlInputFactory.createXMLStreamReader(this.getClass().getClassLoader().getResourceAsStream("testdata/plain-soap-1.1.xml"));
            XmlReaderToWriter.writeAll(xmlStreamReader, xmlStreamWriter);
            xmlStreamWriter.close();

            document = documentBuilderFactory.newDocumentBuilder().parse(new ByteArrayInputStream(baos.toByteArray()));
            NodeList nodeList = document.getElementsByTagNameNS(WSSConstants.TAG_dsig_Signature.getNamespaceURI(), WSSConstants.TAG_dsig_Signature.getLocalPart());
            Assert.assertEquals(nodeList.getLength(), 1);
        }

        //done signature; now test sig-verification:
        {
            // Configure the Validator
            WSSConfig wssConfig = WSSConfig.getNewInstance();
            KerberosTokenValidator validator = new KerberosTokenValidator();
            validator.setContextName("bob");
            validator.setServiceName("bob@service.ws.apache.org");
            wssConfig.setValidator(WSSecurityEngine.BINARY_TOKEN, validator);
            WSSecurityEngine secEngine = new WSSecurityEngine();
            secEngine.setWssConfig(wssConfig);

            CallbackHandler callbackHandler = new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("bob")) {
                            passwordCallback.setPassword("bob".toCharArray());
                        }
                    }
                }
            };

            List<WSSecurityEngineResult> results =
                    secEngine.processSecurityHeader(document, null, callbackHandler, null);
            WSSecurityEngineResult actionResult =
                    WSSecurityUtil.fetchActionResult(results, WSConstants.BST);
            BinarySecurity token =
                    (BinarySecurity) actionResult.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
            Assert.assertTrue(token != null);

            Principal principal = (Principal) actionResult.get(WSSecurityEngineResult.TAG_PRINCIPAL);
            Assert.assertTrue(principal instanceof KerberosPrincipal);
            Assert.assertTrue(principal.getName().contains("alice"));
        }
    }

    @Test
    public void testKerberosSignatureInbound() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        {
            Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

            WSSecHeader secHeader = new WSSecHeader();
            secHeader.insertSecurityHeader(doc);

            KerberosSecurity bst = new KerberosSecurity(doc);
            CallbackHandler callbackHandler = new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("alice")) {
                            passwordCallback.setPassword("alice".toCharArray());
                        }
                    }
                }
            };
            bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
            bst.setID("Id-" + bst.hashCode());

            WSSecSignature sign = new WSSecSignature();
            sign.setSignatureAlgorithm(SignatureMethod.HMAC_SHA1);
            sign.setKeyIdentifierType(WSConstants.CUSTOM_SYMM_SIGNING);
            sign.setCustomTokenId(bst.getID());
            sign.setCustomTokenValueType(WSConstants.WSS_GSS_KRB_V5_AP_REQ);

            SecretKey secretKey = bst.getSecretKey();
            sign.setSecretKey(secretKey.getEncoded());

            sign.build(doc, null, secHeader);
            WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());

            javax.xml.transform.Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
            transformer.transform(new DOMSource(doc), new StreamResult(baos));
        }

        {
            WSSSecurityProperties securityProperties = new WSSSecurityProperties();
            securityProperties.loadSignatureVerificationKeystore(this.getClass().getClassLoader().getResource("receiver.jks"), "default".toCharArray());
            securityProperties.setCallbackHandler(new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("bob")) {
                            passwordCallback.setPassword("bob".toCharArray());
                        }
                    } else if (callbacks[0] instanceof KerberosContextAndServiceNameCallback) {
                        KerberosContextAndServiceNameCallback cb = (KerberosContextAndServiceNameCallback) callbacks[0];
                        cb.setContextName("bob");
                        cb.setServiceName("bob@service.ws.apache.org");
                    }
                }
            });

            final List<KerberosTokenSecurityEvent> kerberosTokenSecurityEvents = new ArrayList<KerberosTokenSecurityEvent>();

            InboundWSSec wsSecIn = WSSec.getInboundWSSec(securityProperties);
            SecurityEventListener securityEventListener = new SecurityEventListener() {
                @Override
                public void registerSecurityEvent(SecurityEvent securityEvent) throws XMLSecurityException {
                    if (securityEvent instanceof KerberosTokenSecurityEvent) {
                        kerberosTokenSecurityEvents.add((KerberosTokenSecurityEvent) securityEvent);
                    }
                }
            };
            XMLStreamReader xmlStreamReader = wsSecIn.processInMessage(xmlInputFactory.createXMLStreamReader(
                    new ByteArrayInputStream(baos.toByteArray())), null, securityEventListener);

            Document document = StAX2DOM.readDoc(documentBuilderFactory.newDocumentBuilder(), xmlStreamReader);

            //header element must still be there
            NodeList nodeList = document.getElementsByTagNameNS(WSSConstants.TAG_dsig_Signature.getNamespaceURI(), WSSConstants.TAG_dsig_Signature.getLocalPart());
            Assert.assertEquals(nodeList.getLength(), 1);
            Assert.assertEquals(nodeList.item(0).getParentNode().getLocalName(), WSSConstants.TAG_wsse_Security.getLocalPart());

            Assert.assertEquals(kerberosTokenSecurityEvents.size(), 1);
            final KerberosTokenSecurityEvent kerberosTokenSecurityEvent = kerberosTokenSecurityEvents.get(0);
            Assert.assertNotNull(kerberosTokenSecurityEvent.getSecurityToken().getSubject());
            Assert.assertTrue(kerberosTokenSecurityEvent.getSecurityToken().getPrincipal() instanceof KerberosPrincipal);
            Assert.assertEquals(kerberosTokenSecurityEvent.getSecurityToken().getPrincipal().getName(), "alice@service.ws.apache.org");
        }
    }

    @Test
    public void testKerberosSignatureKIInbound() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        {
            Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

            WSSecHeader secHeader = new WSSecHeader();
            secHeader.insertSecurityHeader(doc);

            KerberosSecurity bst = new KerberosSecurity(doc);
            CallbackHandler callbackHandler = new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("alice")) {
                            passwordCallback.setPassword("alice".toCharArray());
                        }
                    }
                }
            };
            bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
            bst.setID("Id-" + bst.hashCode());

            WSSecSignature sign = new WSSecSignature();
            sign.setSignatureAlgorithm(SignatureMethod.HMAC_SHA1);
            sign.setKeyIdentifierType(WSConstants.CUSTOM_KEY_IDENTIFIER);
            sign.setCustomTokenValueType(WSConstants.WSS_KRB_KI_VALUE_TYPE);

            SecretKey secretKey = bst.getSecretKey();
            byte[] keyData = secretKey.getEncoded();
            sign.setSecretKey(keyData);

            byte[] digestBytes = WSSecurityUtil.generateDigest(bst.getToken());
            sign.setCustomTokenId(Base64.encode(digestBytes));

            sign.build(doc, null, secHeader);

            WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());

            javax.xml.transform.Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
            transformer.transform(new DOMSource(doc), new StreamResult(baos));
        }

        {
            WSSSecurityProperties securityProperties = new WSSSecurityProperties();
            securityProperties.loadSignatureVerificationKeystore(this.getClass().getClassLoader().getResource("receiver.jks"), "default".toCharArray());
            securityProperties.setCallbackHandler(new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("bob")) {
                            passwordCallback.setPassword("bob".toCharArray());
                        }
                    } else if (callbacks[0] instanceof KerberosContextAndServiceNameCallback) {
                        KerberosContextAndServiceNameCallback cb = (KerberosContextAndServiceNameCallback) callbacks[0];
                        cb.setContextName("bob");
                        cb.setServiceName("bob@service.ws.apache.org");
                    }
                }
            });

            final List<KerberosTokenSecurityEvent> kerberosTokenSecurityEvents = new ArrayList<KerberosTokenSecurityEvent>();

            InboundWSSec wsSecIn = WSSec.getInboundWSSec(securityProperties);
            SecurityEventListener securityEventListener = new SecurityEventListener() {
                @Override
                public void registerSecurityEvent(SecurityEvent securityEvent) throws XMLSecurityException {
                    if (securityEvent instanceof KerberosTokenSecurityEvent) {
                        kerberosTokenSecurityEvents.add((KerberosTokenSecurityEvent) securityEvent);
                    }
                }
            };
            XMLStreamReader xmlStreamReader = wsSecIn.processInMessage(xmlInputFactory.createXMLStreamReader(
                    new ByteArrayInputStream(baos.toByteArray())), null, securityEventListener);

            Document document = StAX2DOM.readDoc(documentBuilderFactory.newDocumentBuilder(), xmlStreamReader);

            //header element must still be there
            NodeList nodeList = document.getElementsByTagNameNS(WSSConstants.TAG_dsig_Signature.getNamespaceURI(), WSSConstants.TAG_dsig_Signature.getLocalPart());
            Assert.assertEquals(nodeList.getLength(), 1);
            Assert.assertEquals(nodeList.item(0).getParentNode().getLocalName(), WSSConstants.TAG_wsse_Security.getLocalPart());

            Assert.assertEquals(kerberosTokenSecurityEvents.size(), 1);
        }
    }

    @Test
    public void testKerberosEncryptionOutbound() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        Document document;
        {
            WSSSecurityProperties securityProperties = new WSSSecurityProperties();
            List<WSSConstants.Action> actions = new ArrayList<WSSConstants.Action>();
            actions.add(WSSConstants.ENCRYPT_WITH_KERBEROS_TOKEN);
            securityProperties.setActions(actions);
            securityProperties.setEncryptionSymAlgorithm(WSSConstants.NS_XENC_AES128);
            securityProperties.setCallbackHandler(new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof KerberosContextAndServiceNameCallback) {
                        KerberosContextAndServiceNameCallback kerberosContextAndServiceNameCallback =
                                (KerberosContextAndServiceNameCallback) callbacks[0];
                        kerberosContextAndServiceNameCallback.setContextName("alice");
                        kerberosContextAndServiceNameCallback.setServiceName("bob@service.ws.apache.org");
                    } else if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("alice")) {
                            passwordCallback.setPassword("alice".toCharArray());
                        }
                    }
                }
            });

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            OutboundWSSec wsSecOut = WSSec.getOutboundWSSec(securityProperties);
            XMLStreamWriter xmlStreamWriter = wsSecOut.processOutMessage(baos, "UTF-8", new ArrayList<SecurityEvent>());
            XMLStreamReader xmlStreamReader = xmlInputFactory.createXMLStreamReader(this.getClass().getClassLoader().getResourceAsStream("testdata/plain-soap-1.1.xml"));
            XmlReaderToWriter.writeAll(xmlStreamReader, xmlStreamWriter);
            xmlStreamWriter.close();

            document = documentBuilderFactory.newDocumentBuilder().parse(new ByteArrayInputStream(baos.toByteArray()));
            NodeList nodeList = document.getElementsByTagNameNS(WSSConstants.TAG_xenc_ReferenceList.getNamespaceURI(), WSSConstants.TAG_xenc_ReferenceList.getLocalPart());
            Assert.assertEquals(1, nodeList.getLength());
        }

        {
            // Configure the Validator
            WSSConfig wssConfig = WSSConfig.getNewInstance();
            KerberosTokenValidator validator = new KerberosTokenValidator();
            validator.setContextName("bob");
            validator.setServiceName("bob@service.ws.apache.org");
            wssConfig.setValidator(WSSecurityEngine.BINARY_TOKEN, validator);
            WSSecurityEngine secEngine = new WSSecurityEngine();
            secEngine.setWssConfig(wssConfig);

            CallbackHandler callbackHandler = new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("bob")) {
                            passwordCallback.setPassword("bob".toCharArray());
                        }
                    }
                }
            };

            List<WSSecurityEngineResult> results =
                    secEngine.processSecurityHeader(document, null, callbackHandler, null);
            WSSecurityEngineResult actionResult =
                    WSSecurityUtil.fetchActionResult(results, WSConstants.BST);
            BinarySecurity token =
                    (BinarySecurity) actionResult.get(WSSecurityEngineResult.TAG_BINARY_SECURITY_TOKEN);
            Assert.assertTrue(token != null);

            Principal principal = (Principal) actionResult.get(WSSecurityEngineResult.TAG_PRINCIPAL);
            Assert.assertTrue(principal instanceof KerberosPrincipal);
            Assert.assertTrue(principal.getName().contains("alice"));
        }
    }

    @Test
    public void testKerberosEncryptionInbound() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        {
            Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

            WSSecHeader secHeader = new WSSecHeader();
            secHeader.insertSecurityHeader(doc);

            KerberosSecurity bst = new KerberosSecurity(doc);
            CallbackHandler callbackHandler = new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("alice")) {
                            passwordCallback.setPassword("alice".toCharArray());
                        }
                    }
                }
            };
            bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
            bst.setID("Id-" + bst.hashCode());

            WSSecEncrypt builder = new WSSecEncrypt();
            builder.setSymmetricEncAlgorithm(WSConstants.AES_256);
            SecretKey secretKey = bst.getSecretKey();
            builder.setSymmetricKey(secretKey);
            builder.setEncryptSymmKey(false);
            builder.setCustomReferenceValue(WSConstants.WSS_GSS_KRB_V5_AP_REQ);
            builder.setEncKeyId(bst.getID());
            builder.build(doc, null, secHeader);
            WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());

            javax.xml.transform.Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
            transformer.transform(new DOMSource(doc), new StreamResult(baos));
        }

        {
            WSSSecurityProperties securityProperties = new WSSSecurityProperties();
            securityProperties.loadDecryptionKeystore(this.getClass().getClassLoader().getResource("receiver.jks"), "default".toCharArray());
            securityProperties.setCallbackHandler(new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("bob")) {
                            passwordCallback.setPassword("bob".toCharArray());
                        }
                    } else if (callbacks[0] instanceof KerberosContextAndServiceNameCallback) {
                        KerberosContextAndServiceNameCallback cb = (KerberosContextAndServiceNameCallback) callbacks[0];
                        cb.setContextName("bob");
                        cb.setServiceName("bob@service.ws.apache.org");
                    }
                }
            });

            final List<KerberosTokenSecurityEvent> kerberosTokenSecurityEvents = new ArrayList<KerberosTokenSecurityEvent>();

            InboundWSSec wsSecIn = WSSec.getInboundWSSec(securityProperties);
            SecurityEventListener securityEventListener = new SecurityEventListener() {
                @Override
                public void registerSecurityEvent(SecurityEvent securityEvent) throws XMLSecurityException {
                    if (securityEvent instanceof KerberosTokenSecurityEvent) {
                        kerberosTokenSecurityEvents.add((KerberosTokenSecurityEvent) securityEvent);
                    }
                }
            };
            XMLStreamReader xmlStreamReader = wsSecIn.processInMessage(xmlInputFactory.createXMLStreamReader(
                    new ByteArrayInputStream(baos.toByteArray())), null, securityEventListener);

            Document document = StAX2DOM.readDoc(documentBuilderFactory.newDocumentBuilder(), xmlStreamReader);

            //header element must still be there
            NodeList nodeList = document.getElementsByTagNameNS(WSSConstants.TAG_wsse_BinarySecurityToken.getNamespaceURI(), WSSConstants.TAG_wsse_BinarySecurityToken.getLocalPart());
            Assert.assertEquals(nodeList.getLength(), 1);
            Assert.assertEquals(nodeList.item(0).getParentNode().getLocalName(), WSSConstants.TAG_wsse_Security.getLocalPart());

            //no encrypted content
            nodeList = document.getElementsByTagNameNS(WSSConstants.TAG_xenc_EncryptedData.getNamespaceURI(), WSSConstants.TAG_xenc_EncryptedData.getLocalPart());
            Assert.assertEquals(nodeList.getLength(), 0);

            Assert.assertEquals(kerberosTokenSecurityEvents.size(), 1);
        }
    }

    @Test
    public void testKerberosEncryptionKIInbound() throws Exception {
        if (!kerberosServerStarted) {
            System.out.println("Skipping test because kerberos server could not be started");
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        {
            Document doc = SOAPUtil.toSOAPPart(SOAPUtil.SAMPLE_SOAP_MSG);

            WSSecHeader secHeader = new WSSecHeader();
            secHeader.insertSecurityHeader(doc);

            KerberosSecurity bst = new KerberosSecurity(doc);
            CallbackHandler callbackHandler = new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("alice")) {
                            passwordCallback.setPassword("alice".toCharArray());
                        }
                    }
                }
            };
            bst.retrieveServiceTicket("alice", callbackHandler, "bob@service.ws.apache.org");
            bst.setID("Id-" + bst.hashCode());

            WSSecEncrypt builder = new WSSecEncrypt();
            builder.setSymmetricEncAlgorithm(WSConstants.AES_128);
            SecretKey secretKey = bst.getSecretKey();
            builder.setSymmetricKey(secretKey);
            builder.setEncryptSymmKey(false);
            builder.setCustomReferenceValue(WSConstants.WSS_KRB_KI_VALUE_TYPE);

            byte[] digestBytes = WSSecurityUtil.generateDigest(bst.getToken());
            builder.setEncKeyId(Base64.encode(digestBytes));

            builder.build(doc, null, secHeader);

            WSSecurityUtil.prependChildElement(secHeader.getSecurityHeader(), bst.getElement());

            javax.xml.transform.Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
            transformer.transform(new DOMSource(doc), new StreamResult(baos));

        }

        {
            WSSSecurityProperties securityProperties = new WSSSecurityProperties();
            securityProperties.loadDecryptionKeystore(this.getClass().getClassLoader().getResource("receiver.jks"), "default".toCharArray());
            securityProperties.setCallbackHandler(new CallbackHandler() {
                @Override
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    if (callbacks[0] instanceof PasswordCallback) {
                        PasswordCallback passwordCallback = (PasswordCallback) callbacks[0];
                        if (passwordCallback.getPrompt().contains("bob")) {
                            passwordCallback.setPassword("bob".toCharArray());
                        }
                    } else if (callbacks[0] instanceof KerberosContextAndServiceNameCallback) {
                        KerberosContextAndServiceNameCallback cb = (KerberosContextAndServiceNameCallback) callbacks[0];
                        cb.setContextName("bob");
                        cb.setServiceName("bob@service.ws.apache.org");
                    }
                }
            });

            final List<KerberosTokenSecurityEvent> kerberosTokenSecurityEvents = new ArrayList<KerberosTokenSecurityEvent>();

            InboundWSSec wsSecIn = WSSec.getInboundWSSec(securityProperties);
            SecurityEventListener securityEventListener = new SecurityEventListener() {
                @Override
                public void registerSecurityEvent(SecurityEvent securityEvent) throws XMLSecurityException {
                    if (securityEvent instanceof KerberosTokenSecurityEvent) {
                        kerberosTokenSecurityEvents.add((KerberosTokenSecurityEvent) securityEvent);
                    }
                }
            };
            XMLStreamReader xmlStreamReader = wsSecIn.processInMessage(xmlInputFactory.createXMLStreamReader(
                    new ByteArrayInputStream(baos.toByteArray())), null, securityEventListener);

            Document document = StAX2DOM.readDoc(documentBuilderFactory.newDocumentBuilder(), xmlStreamReader);

            //header element must still be there
            NodeList nodeList = document.getElementsByTagNameNS(WSSConstants.TAG_wsse_BinarySecurityToken.getNamespaceURI(), WSSConstants.TAG_wsse_BinarySecurityToken.getLocalPart());
            Assert.assertEquals(nodeList.getLength(), 1);
            Assert.assertEquals(nodeList.item(0).getParentNode().getLocalName(), WSSConstants.TAG_wsse_Security.getLocalPart());

            //no encrypted content
            nodeList = document.getElementsByTagNameNS(WSSConstants.TAG_xenc_EncryptedData.getNamespaceURI(), WSSConstants.TAG_xenc_EncryptedData.getLocalPart());
            Assert.assertEquals(nodeList.getLength(), 0);

            Assert.assertEquals(kerberosTokenSecurityEvents.size(), 1);
        }
    }
}
