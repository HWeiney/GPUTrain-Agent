package com.ruoyi.train.core.server.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.train.core.server.service.ServerInventoryService;
import com.ruoyi.train.core.server.ssh.SshGateway;

class ServerAdminControllerTest {
 AnnotationConfigApplicationContext context;
 MockMvc mvc;
 @BeforeEach void setup() {
  context=new AnnotationConfigApplicationContext(Config.class);
  mvc=MockMvcBuilders.standaloneSetup(context.getBean(ServerAdminController.class))
   .setControllerAdvice(new ServerApiExceptionHandler(),new GlobalExceptionHandler()).build();
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }
 void login(String... permissions) {
  var principal=new LoginUser(42L,1L,new SysUser(),Set.of(permissions));
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of()));
 }
 @Test void anonymousAndMissingViewPermissionAreRejected() throws Exception {
  assertEquals(403,mvc.perform(get("/train/servers")).andReturn().getResponse().getStatus());
  login("train:other:view");
  assertEquals(403,mvc.perform(get("/train/servers")).andReturn().getResponse().getStatus());
  login("train:server:view");
  assertEquals(200,mvc.perform(get("/train/servers")).andReturn().getResponse().getStatus());
 }
 @Test void viewPermissionCannotMutateOrPreview() throws Exception {
  login("train:server:view");
  assertEquals(403,mvc.perform(post("/train/servers").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
  assertEquals(403,mvc.perform(post("/train/servers/host-key-preview").contentType(MediaType.APPLICATION_JSON).content("{\"host\":\"gpu\",\"port\":22}")).andReturn().getResponse().getStatus());
  assertEquals(403,mvc.perform(delete("/train/servers/1")).andReturn().getResponse().getStatus());
 }
 @Test void sshErrorsHaveStableCodesAndNeverReturnUnderlyingExceptions() throws Exception {
  login("train:server:view");
  String body=mvc.perform(post("/train/servers/1/probe").contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":7}")).andReturn().getResponse().getContentAsString();
  assertTrue(body.contains("AUTH_FAILED"),body);
  login("train:server:manage");
  body=mvc.perform(post("/train/servers/host-key-preview").contentType(MediaType.APPLICATION_JSON).content("{\"host\":\"gpu\",\"port\":22}")).andReturn().getResponse().getContentAsString();
  assertTrue(body.contains("HOST_KEY_CHANGED"),body);
 }
 @Test void malformedSecretBodyAndUnexpectedFailuresAreSanitized() throws Exception {
  login("train:server:manage");
  String secret="NEVER-LOG-PRIVATE-KEY";
  var result=mvc.perform(post("/train/servers/1/accounts").contentType(MediaType.APPLICATION_JSON)
   .content("{\"ownerUserId\":\""+secret+"\"}")).andReturn().getResponse();
  assertEquals(400,result.getStatus()); assertFalse(result.getContentAsString().contains(secret));
  assertEquals(400,mvc.perform(post("/train/servers/1/accounts").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
 }
 @Test void ownCredentialRotationIsAvailableWithViewButMetadataStillRequiresManage() throws Exception {
  login("train:server:view");
  var rotation=mvc.perform(put("/train/servers/1/accounts/7").contentType(MediaType.APPLICATION_JSON)
   .content("{\"rotate\":true,\"credential\":\"NEVER-LOG-PRIVATE-KEY\"}")).andReturn().getResponse();
  assertEquals(200,rotation.getStatus(),rotation.getContentAsString());
  assertFalse(rotation.getContentAsString().contains("NEVER-LOG-PRIVATE-KEY"));
  assertEquals(403,mvc.perform(put("/train/servers/1").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
 }
 @Configuration @EnableMethodSecurity(proxyTargetClass=true) static class Config {
  @Bean(name="ss") PermissionService permission() { return new PermissionService(); }
  @Bean ServerAdminController controller() { return new ServerAdminController(new ServerInventoryService(null,null,null,null,null,null,null,null) {
   @Override public ServerAccountView rotate(Long s,Long a,RotateCredentials request,Long user) {
    assertEquals(42L,user); assertTrue(request.rotate()); return new ServerAccountView(7L,1L,42L,"alice","PASSWORD",true,"/work");
   }
   @Override public List<ServerEntry> list(Long user) { assertEquals(42L,user); return List.of(); }
   @Override public com.ruoyi.train.core.server.domain.ServerProbeResult probe(Long s,Long a,Long user) { throw new SshGateway.SshException(SshGateway.ErrorCode.AUTH_FAILED); }
   @Override public SshGateway.HostKeyPreview preview(String host,int port,Long user) { throw new SshGateway.SshException(SshGateway.ErrorCode.HOST_KEY_CHANGED); }
   @Override public List<ServerAccountView> assign(Long s,CreateAccount request,Long user) { throw new IllegalArgumentException("NEVER-LOG-PRIVATE-KEY"); }
  }); }
 }
}
