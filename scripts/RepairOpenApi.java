import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Offline contract generator: loads compiled types, never starts Spring or connects to a service. */
class RepairOpenApi {
  static final Map<String,Object> schemas=new TreeMap<>(),paths=new TreeMap<>();
  static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
  static Class<?> dto(String path)throws Exception{return Class.forName("com.gaofeng.repair."+path);}
  static String key(Type type){if(type instanceof ParameterizedType p)return key(p.getRawType())+"Of"+String.join("And",Arrays.stream(p.getActualTypeArguments()).map(RepairOpenApi::key).toList());if(type instanceof Class<?> c)return (c.getEnclosingClass()==null?"":c.getEnclosingClass().getSimpleName()+"_")+c.getSimpleName();return type.getTypeName().replaceAll("[^A-Za-z0-9_]","");}
  static Type resolve(Type type,Map<TypeVariable<?>,Type> variables){return type instanceof TypeVariable<?> v?variables.getOrDefault(v,Object.class):type;}
  static Map<String,Object> schema(Type original,Map<TypeVariable<?>,Type> variables)throws Exception{
    Type type=resolve(original,variables);
    if(type instanceof ParameterizedType parameterized){Class<?> raw=(Class<?>)parameterized.getRawType();if(Collection.class.isAssignableFrom(raw))return map("type","array","items",schema(parameterized.getActualTypeArguments()[0],variables));if(raw.getSimpleName().equals("ResponseEntity"))return schema(parameterized.getActualTypeArguments()[0],variables);Map<TypeVariable<?>,Type> bound=new HashMap<>(variables);for(int i=0;i<raw.getTypeParameters().length;i++)bound.put(raw.getTypeParameters()[i],resolve(parameterized.getActualTypeArguments()[i],variables));return component(raw,key(type),bound);}
    if(!(type instanceof Class<?> c))return map("type","object");
    if(c==void.class || c==Void.class)return null;
    if(c==String.class)return map("type","string");if(c==Long.class)return map("type","string","pattern","^[0-9]{1,19}$","description","64 位标识以十进制字符串传输");
    if(c==boolean.class || c==Boolean.class)return map("type","boolean");if(c==int.class || c==Integer.class || c==long.class)return map("type","integer","format",c==long.class?"int64":"int32");
    if(c==Double.class || c==double.class)return map("type","number","format","double");if(c.getName().equals("java.time.Instant"))return map("type","string","format","date-time");
    if(c.isEnum())return map("type","string","enum",Arrays.stream(c.getEnumConstants()).map(Object::toString).toList());if(c.isRecord())return component(c,key(type),variables);return map("type","object");
  }
  static Map<String,Object> component(Class<?> c,String name,Map<TypeVariable<?>,Type> variables)throws Exception{
    if(!schemas.containsKey(name)){schemas.put(name,map());Map<String,Object> properties=new LinkedHashMap<>();List<String> required=new ArrayList<>();for(RecordComponent field:c.getRecordComponents()){
      Map<String,Object> value=schema(field.getGenericType(),variables);annotations(value,field.getAccessor().getAnnotations());boolean nonnull=Arrays.stream(field.getAccessor().getAnnotations()).anyMatch(a->Set.of("NotNull","NotBlank","NotEmpty").contains(a.annotationType().getSimpleName()));
      if(nonnull)required.add(field.getName());else if(!field.getType().isPrimitive()){if(value.containsKey("$ref"))value=map("allOf",List.of(value),"nullable",true);else value.put("nullable",true);}
      if(field.getAnnotatedType() instanceof AnnotatedParameterizedType ap && value.get("items") instanceof Map items)annotations(items,ap.getAnnotatedActualTypeArguments()[0].getAnnotations());
      properties.put(field.getName(),value);
    }Map<String,Object> result=map("type","object","additionalProperties",false,"properties",properties);if(!required.isEmpty())result.put("required",required);schemas.put(name,result);}
    return map("$ref","#/components/schemas/"+name);
  }
  static void annotations(Map<String,Object> value,Annotation[] annotations)throws Exception{for(Annotation annotation:annotations){String name=annotation.annotationType().getSimpleName();switch(name){case "Pattern"->value.put("pattern",annotation.annotationType().getMethod("regexp").invoke(annotation));case "Min"->value.put("minimum",annotation.annotationType().getMethod("value").invoke(annotation));case "Max"->value.put("maximum",annotation.annotationType().getMethod("value").invoke(annotation));case "Size"->{boolean array="array".equals(value.get("type"));value.put(array?"minItems":"minLength",annotation.annotationType().getMethod("min").invoke(annotation));value.put(array?"maxItems":"maxLength",annotation.annotationType().getMethod("max").invoke(annotation));}case "NotBlank"->value.put("minLength",1);case "NotEmpty"->value.put("array".equals(value.get("type"))?"minItems":"minLength",1);default->{}}}}
  static String[] route(Method method){if(method.isAnnotationPresent(GetMapping.class))return new String[]{"get",first(method.getAnnotation(GetMapping.class).value())};if(method.isAnnotationPresent(PostMapping.class))return new String[]{"post",first(method.getAnnotation(PostMapping.class).value())};if(method.isAnnotationPresent(PutMapping.class))return new String[]{"put",first(method.getAnnotation(PutMapping.class).value())};if(method.isAnnotationPresent(PatchMapping.class))return new String[]{"patch",first(method.getAnnotation(PatchMapping.class).value())};return null;}
  static String first(String[] values){return values.length==0?"":values[0];}
  static Type response(Class<?> controller,Method method)throws Exception{
    String c=controller.getSimpleName(),m=method.getName();if(!method.getGenericReturnType().getTypeName().contains("JsonNode"))return method.getGenericReturnType();
    String target=switch(c){case "AccessController"->switch(m){case "profile"->"access.api.dto.AccessDtos$Session";case "invite"->"access.api.dto.AccessDtos$InvitationCreated";default->"access.api.dto.AccessDtos$MemberView";};case "StoreController"->"access.api.dto.AccessDtos$StoreView";case "AssetController"->"asset.api.dto.AssetDtos$"+(m.startsWith("category")?"CategoryView":"AssetView");case "TicketController"->"ticket.api.dto.TicketDtos$CommandResult";case "AttachmentController"->m.equals("upload")?"attachment.api.dto.AttachmentDtos$AttachmentView":"procurement.api.dto.ProcurementDtos$OrderResult";case "ProcurementController"->"procurement.api.dto.ProcurementDtos$"+(m.equals("update")?"OrderView":m.equals("confirmCosts")?"CostView":"OrderResult");case "OcrController"->"ocr.api.dto.OcrDtos$JobView";case "NotificationController"->"notification.api.dto.NotificationDtos$ReadResult";default->throw new IllegalStateException("Missing response contract: "+c+"."+m);};return dto(target);
  }
  static void operation(Class<?> controller,Method method,String http,String path,String action)throws Exception{
    List<Object> parameters=new ArrayList<>();Map<String,Object> operation=map("tags",List.of(controller.getPackageName().split("\\.")[3]),"operationId",controller.getSimpleName()+"_"+method.getName()+(action==null?"":"_"+action.replace('-','_')),"summary",action==null?controller.getSimpleName()+" · "+method.getName():"工单动作 · "+action);
    Map<String,Object> multipart=new LinkedHashMap<>();List<String> requiredParts=new ArrayList<>();boolean upload=controller.getSimpleName().equals("AttachmentController")&&method.getName().equals("upload");
    for(Parameter parameter:method.getParameters()){
      if(parameter.isAnnotationPresent(RequestBody.class)){operation.put("requestBody",map("required",true,"content",map("application/json",map("schema",schema(parameter.getParameterizedType(),Map.of())))));continue;}
      RequestParam query=parameter.getAnnotation(RequestParam.class);RequestHeader header=parameter.getAnnotation(RequestHeader.class);PathVariable variable=parameter.getAnnotation(PathVariable.class);if(query==null&&header==null&&variable==null)continue;
      String named=header!=null?header.value():variable!=null?variable.value():query.value();String name=named.isBlank()?parameter.getName():named;if(name.equals("action")&&action!=null)continue;
      Map<String,Object> shape=parameter.getType().getSimpleName().equals("MultipartFile")?map("type","string","format","binary"):schema(parameter.getParameterizedType(),Map.of());boolean required=variable!=null||header!=null&&header.required()||query!=null&&query.required()&&query.defaultValue().equals(ValueConstants.DEFAULT_NONE);
      if(variable!=null&&(parameter.getType()==long.class))shape=map("type","string","pattern","^[1-9][0-9]{0,18}$");
      if(header!=null&&name.equals("Idempotency-Key"))shape=map("type","string","format","uuid","description","重试复用相同 UUID；同 key 不同请求体返回 409，有效期 24 小时");
      if(query!=null&&!query.defaultValue().equals(ValueConstants.DEFAULT_NONE)){String d=query.defaultValue();shape.put("default",parameter.getType()==int.class?Integer.valueOf(d):parameter.getType()==boolean.class?Boolean.valueOf(d):d);}
      if(name.equals("size")){shape.put("minimum",1);shape.put("maximum",100);}if(name.equals("page")){shape.put("minimum",1);shape.put("maximum",1000000);}
      if(upload&&query!=null){multipart.put(name,shape);if(required)requiredParts.add(name);}else parameters.add(map("name",name,"in",variable!=null?"path":header!=null?"header":"query","required",required,"schema",shape));
    }
    if(http.equals("post") && (path.startsWith("/auth/") || path.equals("/repair/invitations/accept"))) parameters.add(map("name","X-Repair-CSRF","in","header","required",true,"schema",map("type","string","enum",List.of("1")),"description","浏览器 AJAX 专用头；Origin 必须精确匹配允许的前端来源。"));
    if(path.equals("/auth/logout"))parameters.add(map("name","Authorization","in","header","required",false,"schema",map("type","string"),"description","可选 Bearer Access Token：刷新 Cookie 缺失时撤销该令牌指向的会话；过期或无效令牌不阻止清除 Cookie。"));
    if(Set.of("/auth/token/refresh","/auth/logout").contains(path))parameters.add(map("name","__Host-repair_refresh","in","cookie","required",path.endsWith("refresh"),"schema",map("type","string"),"description","HttpOnly / Secure / SameSite=Lax / host-only Cookie；仅本地 HTTP 开发使用 repair_refresh_local。请求体不接受刷新令牌。"));
    if(upload)operation.put("requestBody",map("required",true,"content",map("multipart/form-data",map("schema",map("type","object","properties",multipart,"required",requiredParts,"additionalProperties",false)))));
    if(!parameters.isEmpty())operation.put("parameters",parameters);
    String success="200";if(Set.of("/auth/logout","/auth/password/reset").contains(path))success="204";else if(controller.getSimpleName().equals("OcrController")&&http.equals("post"))success="202";else if((Set.of("StoreController","TicketController").contains(controller.getSimpleName())&&method.getName().equals("create"))||controller.getSimpleName().equals("AssetController")&&Set.of("create","category").contains(method.getName())||controller.getSimpleName().equals("AccessController")&&method.getName().equals("invite")||upload||controller.getSimpleName().equals("ProcurementController")&&method.getName().equals("create"))success="201";
    Map<String,Object> responses=new LinkedHashMap<>();Map<String,Object> ok=map("description","成功","headers",map("X-Request-Id",map("schema",map("type","string"))));
    if(method.getName().equals("content"))ok.put("content",map("image/png",map("schema",map("type","string","format","binary")),"image/jpeg",map("schema",map("type","string","format","binary"))));
    else if(path.equals("/repair/reports/export"))ok.put("content",map("text/csv",map("schema",map("type","string","description","UTF-8 BOM；每张工单一行；提交时间筛选；最多 10,000 行"))));
    else{Map<String,Object> output=schema(response(controller,method),Map.of());if(output!=null)ok.put("content",map("application/json",map("schema",output)));}
    if(Set.of("/auth/login","/auth/token/refresh","/auth/logout","/auth/password/reset").contains(path)) ((Map<String,Object>)ok.get("headers")).put("Set-Cookie",map("schema",map("type","string"),"description","签发、轮换或清除 HttpOnly 刷新 Cookie；响应 JSON 不包含 Refresh Token。"));
    if(!path.equals("/auth/register"))responses.put(success,ok);for(String code:List.of("400","401","403","404","409","413","422","429","503"))responses.put(code,map("$ref","#/components/responses/Error"));operation.put("responses",responses);
    if(path.startsWith("/auth/")&&!path.equals("/auth/me") || path.equals("/repair/invitations/preview")||path.equals("/repair/invitations/accept"))operation.put("security",List.of());
    if(path.equals("/auth/token/refresh"))operation.put("security",List.of(map("cookieAuth",List.of())));
    if(path.equals("/auth/login"))operation.put("description","签发绑定 sid 的 Access Token 到响应体，Refresh Token 仅写 HttpOnly Cookie；登录会话固定最长 7 天。浏览器访问令牌只保存在内存。");
    if(path.equals("/auth/logout"))operation.put("description","撤销 Cookie 对应当前 sid，立即阻断该会话所有已签发访问令牌并清除 Cookie；其他设备保持有效。没有有效 Cookie 时幂等返回 204。撤销失败返回错误，客户端不得假报退出成功。");
    if(path.equals("/auth/password/reset"))operation.put("description","成功后事务内更新密码、递增 auth_version 并撤销账号全部 sid；旧访问令牌与刷新令牌立即失效。");
    if(action!=null)operation.put("description","以工单 allowedActions、当前角色与状态为准。expectedVersion 必须为最新工单版本；材料未收齐禁止 start。confirm/return 需要有效门店范围，总部可代验收。具体必填字段见开发文档 04-api-and-ui.md。");
    if(path.contains("purchase-orders")||path.contains("ocr")||path.contains("costs")||path.contains("reports"))operation.putIfAbsent("description","完整采购、OCR、费用和报表仅总部可访问；材料摘要及收货接口按工单当前参与权限访问。金额为十进制字符串，时间为 ISO-8601 UTC。详见开发文档。");
    ((Map<String,Object>)paths.computeIfAbsent(path,p->new LinkedHashMap<>())).put(http,operation);
  }
  public static void main(String[] args)throws Exception{
    Path classes=Path.of("target/classes");try(var files=Files.walk(classes.resolve("com/gaofeng/repair"))){for(Path file:files.filter(p->p.toString().endsWith("Controller.class")).sorted().toList()){
      String name=classes.relativize(file).toString().replace('\\','.').replace('/','.').replaceAll("\\.class$","");Class<?> c=Class.forName(name);RequestMapping prefix=c.getAnnotation(RequestMapping.class);if(prefix==null)continue;
      for(Method m:Arrays.stream(c.getDeclaredMethods()).sorted(Comparator.comparing(Method::getName)).toList()){String[] route=route(m);if(route==null)continue;String path=(first(prefix.value())+route[1]).replaceFirst("^/api/v1","");
        if(path.endsWith("/{action}"))for(String action:List.of("asset-link","approve","reject","cancel","assign","reassign","withdraw-assignment","accept","appointment","start","work-records","submit-result","confirm","return"))operation(c,m,route[0],path.replace("{action}",action),action);else operation(c,m,route[0],path,null);
      }
    }}
    if(schemas.get("SendCodeRequest")!=null){
      ((Map<String,Object>)((Map<String,Object>)schemas.get("SendCodeRequest")).get("properties")).put("scene",map("type","string","enum",List.of("RESET_PASSWORD"),"description","当前邀请制仅开放找回密码验证码；REGISTER 返回 REGISTRATION_DISABLED，LOGIN 返回 VERIFICATION_SCENE_DISABLED。"));
    }
    Map<String,Object> error=schema(dto("common.RepairErrors$Error"),Map.of());Map<String,Object> document=map("openapi","3.0.3","info",map("title","门店设施报修 API","version","1.0.0","description","阶段 1–6 实现合同。内部受邀成员访问，私有图片不提供匿名直链；OCR 默认关闭；业务通知仅站内，SMTP 仅用于注册验证/找回密码（当前邀请制不开放注册）。以代码和 04-api-and-ui.md 的业务校验为准。"),"servers",List.of(map("url","/api/v1")),"security",List.of(map("bearerAuth",List.of())),"paths",paths,"components",map("schemas",schemas,"securitySchemes",map("bearerAuth",map("type","http","scheme","bearer","bearerFormat","JWT"),"cookieAuth",map("type","apiKey","in","cookie","name","__Host-repair_refresh","description","生产 HTTPS 的 HttpOnly 刷新 Cookie；本地 HTTP 模式使用 repair_refresh_local")),"responses",map("Error",map("description","统一业务错误，包含 requestId；409 保留输入并刷新版本；READ_CACHE_BUSY 返回 503 和 Retry-After: 1，稍后重试；OCR 不可用可手填","content",map("application/json",map("schema",error))))));
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("docs/repair-system/openapi.json").toFile(),document);System.out.println("Generated "+paths.size()+" paths and "+schemas.size()+" schemas offline");
  }
}

