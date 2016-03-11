package org.sms.saml.controller;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletInputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.opensaml.common.SAMLVersion;
import org.opensaml.saml2.core.Artifact;
import org.opensaml.saml2.core.ArtifactResolve;
import org.opensaml.saml2.core.ArtifactResponse;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.Attribute;
import org.opensaml.saml2.core.AttributeStatement;
import org.opensaml.saml2.core.AuthnRequest;
import org.opensaml.saml2.core.Issuer;
import org.opensaml.saml2.core.Response;
import org.opensaml.saml2.core.Status;
import org.opensaml.saml2.core.StatusCode;
import org.opensaml.xml.schema.XSString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sms.SysConstants;
import org.sms.component.idfactory.UUIDFactory;
import org.sms.organization.user.entity.User;
import org.sms.organization.user.service.UserService;
import org.sms.project.app.entity.App;
import org.sms.project.app.service.AppService;
import org.sms.project.authentication.entity.SysAuthentication;
import org.sms.project.authentication.service.SysAuthenticationService;
import org.sms.project.helper.SessionHelper;
import org.sms.project.security.SampleAuthenticationManager;
import org.sms.project.sso.AuthenRequestHelper;
import org.sms.project.sso.SSOHelper;
import org.sms.saml.service.SamlService;
import org.sms.util.HttpUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 处理SAML请求的数据（SSO验证登录）
 * @author Sunny
 * @since 1.8.0
 */
@Controller
@RequestMapping("/SAML2")
public class SamlController {

  @Autowired
  private SamlService samlService;
  
  @Autowired
  private AppService appService;
  
  @Autowired
  private UserService userService;
  
  @Autowired
  private SysAuthenticationService sysAuthenticationService;
  
  private Logger logger = LoggerFactory.getLogger(SamlController.class.getName());

  /**
   * IDP 接受SP端的Artifact
   * @param request
   * @param response
   * @return
   */
  @RequestMapping("/receiveSPArtifact")
  public String receiveSPArtifact(HttpServletRequest request, HttpServletResponse response) {
    
    //获取Artifact
    String artifactBase64 = request.getParameter(SysConstants.ARTIFACT_KEY);
    if (null == artifactBase64) {
      throw new RuntimeException("artifact不能为空");
    }
    final ArtifactResolve artifactResolve = samlService.buildArtifactResolve();
    final Artifact artifact = (Artifact) samlService.buildStringToXMLObject(artifactBase64);
    artifactResolve.setArtifact(artifact);
    //对artifactResolve对象进行签名操作
    samlService.signXMLObject(artifactResolve);
    String artifactParam = samlService.buildXMLObjectToString(artifactResolve);
    String postResult = HttpUtil.doPost(SysConstants.SP_ARTIFACT_RESOLUTION_SERVICE, artifactParam);
    final ArtifactResponse artifactResponse = (ArtifactResponse) samlService.buildStringToXMLObject(postResult);
    final Status status = artifactResponse.getStatus();
    StatusCode statusCode = status.getStatusCode();
    String codeValue = statusCode.getValue();
    
    //判断SAML的StatusCode
    if (codeValue == null || !codeValue.equals(StatusCode.SUCCESS_URI)) {
      throw new RuntimeException("认证失败");
    }
    final String inResponseTo = artifactResponse.getInResponseTo();
    final String artifactResolveID = artifactResolve.getID();
    if (null == inResponseTo || !inResponseTo.equals(artifactResolveID)) {
      throw new RuntimeException("认证失败");
    }
    final AuthnRequest authnRequest = (AuthnRequest) artifactResponse.getMessage();
    if (authnRequest == null) {
      throw new RuntimeException("authnRequest不能为空");
    }
    //获取SP的消费URL，下一步回调需要用到
    final String customerServiceUrl = authnRequest.getAssertionConsumerServiceURL();
    request.setAttribute(SysConstants.ACTION_KEY, customerServiceUrl);
    HttpSession session = request.getSession(false);
    session.setAttribute(SysConstants.ACTION_KEY, customerServiceUrl);
    final SAMLVersion samlVersion = authnRequest.getVersion();
    
    //判断版本是否支持
    if (null == samlVersion || !SAMLVersion.VERSION_20.equals(samlVersion)) {
      throw new RuntimeException("SAML版本错误，只支持2.0");
    }
    
    final Issuer issuer = authnRequest.getIssuer();
    final String appName = issuer.getValue();
    
    //判断issure的里面的值是否在SSO系统中注册过
    final App app = appService.findAppByAppName(appName.trim());
    if (app == null) {
      throw new RuntimeException("不支持当前系统: " + appName);
    }
    final String requestID = authnRequest.getID();
    logger.debug("AuthRequest的ID为：" + requestID);
    
    //根据AuthnRequest判断用户是否登录
    //判断令牌是否存在,如果令牌不存在则直接跳转到登录页面
    final SysAuthentication sysAuthen = sysAuthenticationService.queryBySSOToken(requestID);
    if (sysAuthen.getSso_token() == null) {
      return "redirect:/loginPage";
    }
    //判断令牌是否过期，如果令牌过期则直接
    Timestamp expireTimestamp = sysAuthen.getExpire_time();
    long expireTime = expireTimestamp.getTime();
    long nowTime = System.currentTimeMillis();
    if (expireTime > nowTime) {
      return "redirect:/loginPage";
    }
    final Artifact idpArtifact = samlService.buildArtifact();
    final Response samlResponse = samlService.buildResponse(UUIDFactory.INSTANCE.getUUID());
    String userid = sysAuthen.getSubject_id();
    long id = Long.parseLong(userid);
    User user = userService.find(id);
    samlService.addAttribute(samlResponse, user);
    SSOHelper.INSTANCE.put(idpArtifact.getArtifact(), samlResponse);
    request.setAttribute(SysConstants.ARTIFACT_KEY, samlService.buildXMLObjectToString(idpArtifact));
    return "/saml/idp/send_artifact_to_sp";
  }

  /**
   * IDP 接受SP端的Artifact
   * @param request
   * @param response
   * @return
   */
  @ResponseBody
  @RequestMapping("/IDPArtifactResolution")
  public String IDPArtifactResolution(HttpServletRequest request, HttpServletResponse response) {
    ServletInputStream inputStream;
    try {
      inputStream = request.getInputStream();
      String result = HttpUtil.readInputStream(inputStream);
      ArtifactResolve artifactResolve = (ArtifactResolve) samlService.buildStringToXMLObject(result.trim());
      ArtifactResponse artifactResponse = samlService.buildArtifactResponse();
      artifactResponse.setInResponseTo(artifactResolve.getID());
      Artifact artifact = artifactResolve.getArtifact();
      Response samlResponse = SSOHelper.INSTANCE.get(artifact.getArtifact());
      if (null == samlResponse) {
        return null;
      }
      artifactResponse.setMessage(samlResponse);
      SSOHelper.INSTANCE.remove(artifact.getArtifact());
      SysAuthentication sysAuthen = new SysAuthentication();
      sysAuthen.setSso_token(samlResponse.getID());
      sysAuthen.setId(System.currentTimeMillis());
      sysAuthen.setSubject_id(1 + "");
      sysAuthenticationService.create(sysAuthen);
      return samlService.buildXMLObjectToString(artifactResponse);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * SP 接受SP端的Artifact
   * @param request
   * @param response
   * @return
   */
  @RequestMapping("/receiveIDPArtifact")
  public String receiveIDPArtifact(HttpServletRequest request, HttpServletResponse response) {
    String spArtifact = request.getParameter(SysConstants.ARTIFACT_KEY);
    ArtifactResolve artifactResolve = samlService.buildArtifactResolve();
    Artifact artifact = (Artifact) samlService.buildStringToXMLObject(spArtifact);
    artifactResolve.setArtifact(artifact);
    samlService.signXMLObject(artifactResolve);
    String requestStr = samlService.buildXMLObjectToString(artifactResolve);
    String postResult = HttpUtil.doPost(SysConstants.IDP_ARTIFACT_RESOLUTION_SERVICE, requestStr);
    ArtifactResponse artifactResponse = (ArtifactResponse) samlService.buildStringToXMLObject(postResult);
    Response samlResponse = (Response) artifactResponse.getMessage();
    List<Assertion> assertions = samlResponse.getAssertions();
    if (null == assertions || assertions.size() == 0) {
      throw new RuntimeException("无法获取断言，请重新发起请求！！！");
    }
    Assertion assertion = samlResponse.getAssertions().get(0);
    if (assertion == null) {
      request.setAttribute(SysConstants.ERROR_LOGIN, true);
    } else {
      boolean isSigned = samlService.validate(assertion);
//      if (!isSigned) {
//        request.setAttribute(SysConstants.ERROR_LOGIN, true);
//      } else {
        HttpSession session = request.getSession(false);
        User user = new User();
        List<AttributeStatement> arrtibuteStatements = assertion.getAttributeStatements();
        if (null == arrtibuteStatements || arrtibuteStatements.size() == 0) {
          throw new RuntimeException("无法获取属性列表，请重新发起请求");
        }
        AttributeStatement attributeStatement = assertion.getAttributeStatements().get(0);
        List<Attribute> list = attributeStatement.getAttributes();
        list.forEach(pereAttribute -> {
          String name = pereAttribute.getName();
          XSString value = (XSString) pereAttribute.getAttributeValues().get(0);
          String valueString = value.getValue();
          if (name.endsWith("Name")) {
            user.setName(valueString);
          } else if (name.equals("Id")) {
            user.setId(Long.parseLong(valueString));
          } else if (name.equals("LoginId")) {
            user.setLogin_id(valueString);
          } else if (name.equals("Email")) {
            user.setEmail(valueString);
          }
        });
        addSSOCookie(response, samlResponse.getID());
        session.setAttribute(SysConstants.LOGIN_USER, user);
        putAuthnToSecuritySession("admin", "admin");
        request.setAttribute(SysConstants.ERROR_LOGIN, false);
      }
//    }
    return "/saml/sp/redirect";
  }

  public void putAuthnToSecuritySession(String name, String password) {
    final List<String> list = new ArrayList<String>();
    list.add("ADMIN");
    AuthenticationManager authenticationManager = new SampleAuthenticationManager(list);
    Authentication request = new UsernamePasswordAuthenticationToken(name, password);
    Authentication result = authenticationManager.authenticate(request);
    SecurityContextHolder.getContext().setAuthentication(result);
  }

  /**
   * SP 接受IDP端的Artifact 并返回给IDP ArtifactResponse 接受SP端的Artifact
   * @param request
   * @param response
   * @return
   */
  @ResponseBody
  @RequestMapping("/SPArtifactResolution")
  public String SPArtifactResolution(HttpServletRequest request, HttpServletResponse response) {
    try {
      ServletInputStream inputStream = request.getInputStream();
      String result = HttpUtil.readInputStream(inputStream);
      ArtifactResponse artifactResponse = samlService.buildArtifactResponse();
      
      /**
       * 验证签名
       */
      boolean isSign = samlService.validate(result.trim());
      if (!isSign) {
        /**
         * 添加认证失败状态
         */
        artifactResponse.setStatus(samlService.getStatusCode(false));
      } else {
        ArtifactResolve artifactResolve = (ArtifactResolve) samlService.buildStringToXMLObject(result.trim());
        artifactResponse.setInResponseTo(artifactResolve.getID());
        artifactResponse.setStatus(samlService.getStatusCode(true));
        Artifact artifact = artifactResolve.getArtifact();
        AuthnRequest authnRequest = AuthenRequestHelper.INSTANCE.get(artifact.getArtifact());
        if (null == authnRequest) {
          return null;
        }
        artifactResponse.setMessage(authnRequest);
        artifactResponse.setInResponseTo(artifactResolve.getID());
      }
      return samlService.buildXMLObjectToString(artifactResponse);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * SP 发送Artifact到IDP 生成SP端的Artifact
   * @param request
   * @param response
   * @return
   */
  @RequestMapping("/sendArtifactToIDP")
  public String sendSPArtifact(HttpServletRequest request, HttpServletResponse response) {
    Artifact artifact = samlService.buildArtifact();
    String artifactBase64 = samlService.buildXMLObjectToString(artifact);
    String token = UUIDFactory.INSTANCE.getUUID();
    /**
     * 把生成的Artifact放入Session
     */
    SessionHelper.put(request, artifact.getArtifact(), artifact);
    request.setAttribute(SysConstants.ARTIFACT_KEY, artifactBase64);
    request.setAttribute(SysConstants.TOKEN_KEY, token);
    String sso_token_key = (String) SessionHelper.get(request, SysConstants.SSO_TOKEN_KEY);
    if (null == sso_token_key) {
      sso_token_key = SysConstants.SAML_ID_PREFIX_CHAR + UUIDFactory.INSTANCE.getUUID();
    }
    AuthnRequest authnRequest = samlService.buildAuthnRequest(sso_token_key, SysConstants.SPRECEIVESPARTIFACT_URL);
    AuthenRequestHelper.INSTANCE.put(artifact.getArtifact(), authnRequest);
    return "/saml/sp/send_artifact_to_idp";
  }
  
  public void addSSOCookie(HttpServletResponse response, String string) {
    Cookie cookie = new Cookie(SysConstants.SSO_TOKEN_KEY,string);
    cookie.setDomain(".soaer.com");
    cookie.setPath("/");
    cookie.setMaxAge(24 *60 * 60);
    response.addCookie(cookie);
  }
}