package com.ruoyi.train.core.server;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.server.mapper.ServerInventoryMapper;

class ServerInventoryMapperTest {
 @Test void loadsInventoryStatementsAndRestrictsFreshOnlineCountsToOwners() throws Exception {
  Configuration config=new Configuration();
  for(String name: new String[]{"ServerMapper","ServerInventoryMapper"}) {
   String path="mapper/train/"+name+".xml";
   try(var stream=getClass().getClassLoader().getResourceAsStream(path)) {
    new XMLMapperBuilder(stream,config,path,config.getSqlFragments()).parse();
   }
  }
  for(var method:ServerInventoryMapper.class.getDeclaredMethods()) assertTrue(config.hasStatement(ServerInventoryMapper.class.getName()+"."+method.getName()));
  var statement=config.getMappedStatement(ServerInventoryMapper.class.getName()+".countOnline");
  var params=Map.of("ownerUserId",42L,"isAdmin",false,"freshAfter",Instant.now().minusSeconds(30),"now",Instant.now());
  String sql=statement.getBoundSql(params).getSql();
  assertTrue(sql.contains("a.owner_user_id = ?"));
  assertTrue(sql.contains("a.last_probe_at = a.last_success_at"));
  assertTrue(sql.contains("s.enabled = 1")); assertTrue(sql.contains("a.enabled = 1"));
  assertFalse(sql.contains("credential"));
 }
}
