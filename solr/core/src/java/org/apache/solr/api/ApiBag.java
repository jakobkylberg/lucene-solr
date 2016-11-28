/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.ValidatingJsonMap;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.PluginBag;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.util.CommandOperation;
import org.apache.solr.util.JsonSchemaValidator;
import org.apache.solr.util.PathTrie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.client.solrj.SolrRequest.SUPPORTED_METHODS;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.common.util.ValidatingJsonMap.ENUM_OF;
import static org.apache.solr.common.util.ValidatingJsonMap.NOT_NULL;
import static org.apache.solr.common.util.StrUtils.formatString;

public class ApiBag {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Map<String, PathTrie<Api>> apis = new ConcurrentHashMap<>();

  public static ValidatingJsonMap getResource(String name) {
    InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
    if (is == null)
      throw new RuntimeException("invalid API spec :" + name );
    ValidatingJsonMap map1 = null;
    try {
      map1 = ValidatingJsonMap.fromJSON(is);
    } catch (Exception e) {
      log.error("Error in JSON : " + name, e);
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
    if (map1 == null) throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Empty value for " + name);

    return ValidatingJsonMap.getDeepCopy(map1, 5, false);
  }



  public synchronized void register(Api api, Map<String, String> nameSubstitutes) {
    try {
      validateAndRegister(api, nameSubstitutes);
    } catch (Exception e) {
      log.error("Unable to register plugin:" + api.getClass().getName() + "with spec :" + Utils.toJSONString(api.getSpec()), e);
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      } else {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
      }

    }
  }

  private void validateAndRegister(Api api, Map<String, String> nameSubstitutes) {
    ValidatingJsonMap spec = api.getSpec();
    Api introspect = new IntrospectApi(api);
    List<String> methods = spec.getList("methods", ENUM_OF, SUPPORTED_METHODS);
    for (String method : methods) {
      PathTrie<Api> registry = apis.get(method);

      if (registry == null) apis.put(method, registry = new PathTrie<>(ImmutableSet.of("_introspect")));
      ValidatingJsonMap url = spec.getMap("url", NOT_NULL);
      ValidatingJsonMap params = url.getMap("params", null);
      if (params != null) {
        for (Object o : params.keySet()) {
          ValidatingJsonMap param = params.getMap(o.toString(), NOT_NULL);
          param.get("type", ENUM_OF, KNOWN_TYPES);
        }
      }
      List<String> paths = url.getList("paths", NOT_NULL);
      ValidatingJsonMap parts = url.getMap("parts", null);
      if (parts != null) {
        Set<String> wildCardNames = getWildCardNames(paths);
        for (Object o : parts.keySet()) {
          if (!wildCardNames.contains(o.toString()))
            throw new RuntimeException("" + o + " is not a valid part name");
          ValidatingJsonMap pathMeta = parts.getMap(o.toString(), NOT_NULL);
          pathMeta.get("type", ENUM_OF, ImmutableSet.of("enum", "string", "int", "number", "boolean"));
        }
      }
      verifyCommands(api.getSpec());
      for (String path : paths) {
        registry.insert(path, nameSubstitutes, api);
        registry.insert(path + INTROSPECT, nameSubstitutes, introspect);
      }
    }
  }

  public static class IntrospectApi extends  Api {
    Api baseApi;

    protected IntrospectApi( Api base) {
      super(EMPTY_SPEC);
      this.baseApi = base;
    }

    public void call(SolrQueryRequest req, SolrQueryResponse rsp) {

      String cmd = req.getParams().get("command");
      ValidatingJsonMap result = null;
      if (cmd == null) {
        result = baseApi.getSpec();
      } else {
        ValidatingJsonMap specCopy = ValidatingJsonMap.getDeepCopy(baseApi.getSpec(), 5, true);
        ValidatingJsonMap commands = specCopy.getMap("commands", null);
        if (commands != null) {
          ValidatingJsonMap m = commands.getMap(cmd, null);
          specCopy.put("commands", Collections.singletonMap(cmd, m));
        }
        result = specCopy;
      }
      List l = (List) rsp.getValues().get("spec");
      if (l == null) rsp.getValues().add("spec", l = new ArrayList());
      l.add(result);
    }
  }

  public static Map<String, JsonSchemaValidator> getParsedSchema(ValidatingJsonMap commands) {
    Map<String,JsonSchemaValidator> validators =  new HashMap<>();
    for (Object o : commands.entrySet()) {
      Map.Entry cmd = (Map.Entry) o;
      try {
        validators.put((String) cmd.getKey(), new JsonSchemaValidator((Map) cmd.getValue()));
      } catch (Exception e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error in api spec" , e);
      }
    }
    return validators;
  }


  private void verifyCommands(ValidatingJsonMap spec) {
    ValidatingJsonMap commands = spec.getMap("commands", null);
    if (commands == null) return;
    getParsedSchema(commands);

  }

  private Set<String> getWildCardNames(List<String> paths) {
    Set<String> wildCardNames = new HashSet<>();
    for (String path : paths) {
      List<String> p = PathTrie.getPathSegments(path);
      for (String s : p) {
        String wildCard = PathTrie.templateName(s);
        if (wildCard != null) wildCardNames.add(wildCard);
      }
    }
    return wildCardNames;
  }


  public Api lookup(String path, String httpMethod, Map<String, String> parts) {
    if (httpMethod == null) {
      for (PathTrie<Api> trie : apis.values()) {
        Api api = trie.lookup(path, parts);
        if (api != null) return api;
      }
      return null;
    } else {
      PathTrie<Api> registry = apis.get(httpMethod);
      if (registry == null) return null;
      return registry.lookup(path, parts);
    }
  }

  public static SpecProvider getSpec(final String name) {
    return () -> {
      String jsonName = APISPEC_LOCATION + name + ".json";
      ValidatingJsonMap map = getResource(jsonName);
      ValidatingJsonMap result = map;
      ValidatingJsonMap cmds = result.getMap("commands", null);
      if (cmds != null) {
        Map  commands2BReplaced = new ValidatingJsonMap();
        for (Object o : cmds.keySet()) {
          Object val = cmds.get(o);
          Map m = (Map) val;
          String include = (String) m.get("#include");
          if (include != null) {
            ValidatingJsonMap cmdSpec = getResource(APISPEC_LOCATION + include + ".json");
            m = ValidatingJsonMap.getDeepCopy(m, 4, true);
            m.remove("#include");
            m.putAll(cmdSpec);
            commands2BReplaced.put(o.toString(), m);
          }
        }

        if (!commands2BReplaced.isEmpty()) {
          ValidatingJsonMap mapCopy = ValidatingJsonMap.getDeepCopy(result, 4, true);
          mapCopy.getMap("commands", NOT_NULL).putAll(commands2BReplaced);
          result = ValidatingJsonMap.getDeepCopy(mapCopy, 4, false);
        }
      }

      return result;
    };


  }

  public static class ReqHandlerToApi extends Api implements PermissionNameProvider {
    SolrRequestHandler rh;

    public ReqHandlerToApi(SolrRequestHandler rh, SpecProvider spec) {
      super(spec);
      this.rh = rh;
    }

    @Override
    public void call(SolrQueryRequest req, SolrQueryResponse rsp) {
      rh.handleRequest(req, rsp);
    }

    @Override
    public Name getPermissionName(AuthorizationContext ctx) {
      if (rh instanceof PermissionNameProvider) {
        return ((PermissionNameProvider) rh).getPermissionName(ctx);
      }
      return null;
    }
  }

  public static List<Api> wrapRequestHandlers(final SolrRequestHandler rh, String... specs){
    ImmutableList.Builder<Api> b = ImmutableList.builder();
    for (String spec : specs) b.add(new ReqHandlerToApi(rh, ApiBag.getSpec(spec)));
    return b.build();
  }

  public static final String APISPEC_LOCATION = "apispec/";
  public static final String INTROSPECT = "/_introspect";


  public static final SpecProvider EMPTY_SPEC = () -> ValidatingJsonMap.EMPTY;
  public static final String HANDLER_NAME = "handlerName";
  public static final Set<String> KNOWN_TYPES = ImmutableSet.of("string", "boolean", "list", "int", "double", "object");

  public PathTrie<Api> getRegistry(String method) {
    return apis.get(method);
  }

  public void registerLazy(PluginBag.PluginHolder<SolrRequestHandler> holder, PluginInfo info) {
    String specName = info.attributes.get("spec");
    if (specName == null) specName = "emptySpec";
    register(new LazyLoadedApi(ApiBag.getSpec(specName), holder), Collections.singletonMap(HANDLER_NAME, info.attributes.get(NAME)));
  }

  public static SpecProvider constructSpec(PluginInfo info) {
    Object specObj = info == null ? null : info.attributes.get("spec");
    if (specObj == null) specObj = "emptySpec";
    if (specObj instanceof Map) {
      Map map = (Map) specObj;
      return () -> ValidatingJsonMap.getDeepCopy(map, 4, false);
    } else {
      return ApiBag.getSpec((String) specObj);
    }
  }

  public static List<CommandOperation> getCommandOperations(Reader reader, Map<String,JsonSchemaValidator> validators, boolean validate) {
    List<CommandOperation> parsedCommands = null;
    try {
      parsedCommands = CommandOperation.parse(reader);
    } catch (IOException e) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
    }
    if (validators == null || !validate) {    // no validation possible because we do not have a spec
      return parsedCommands;
    }

    List<CommandOperation> commandsCopy = CommandOperation.clone(parsedCommands);

    for (CommandOperation cmd : commandsCopy) {
      JsonSchemaValidator validator = validators.get(cmd.name);
      if (validator == null) {
        cmd.addError(formatString("Unknown operation ''{0}'' available ops are ''{1}''", cmd.name,
            validators.keySet()));
        continue;
      } else {
        List<String> errs = validator.validateJson(cmd.getCommandData());
        if(errs != null) for (String err : errs) cmd.addError(err);
      }

    }
    List<Map> errs = CommandOperation.captureErrors(commandsCopy);
    if (!errs.isEmpty()) {
      throw new ExceptionWithErrObject(SolrException.ErrorCode.BAD_REQUEST, "Error in command payload", errs);
    }
    return commandsCopy;
  }

  public static class ExceptionWithErrObject extends SolrException {
    private List<Map> errs;

    public ExceptionWithErrObject(ErrorCode code, String msg, List<Map> errs) {
      super(code, msg);
      this.errs = errs;
    }

    public List<Map> getErrs(){
      return errs;
    }
  }

  public static class LazyLoadedApi extends Api {

    private final PluginBag.PluginHolder<SolrRequestHandler> holder;
    private Api delegate;

    protected LazyLoadedApi(SpecProvider specProvider, PluginBag.PluginHolder<SolrRequestHandler> lazyPluginHolder) {
      super(specProvider);
      this.holder = lazyPluginHolder;
    }

    @Override
    public void call(SolrQueryRequest req, SolrQueryResponse rsp) {
      if (!holder.isLoaded()) {
        delegate = new ReqHandlerToApi(holder.get(), ApiBag.EMPTY_SPEC);
      }
      delegate.call(req, rsp);
    }
  }

}
