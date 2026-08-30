package com.github.letsrokk.updater;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CatalogReconciler {
 private static final Pattern VERSION=Pattern.compile("(?m)^\\s*version:\\s*[\\\"]?(3\\.\\d+\\.\\d+)[\\\"]?\\s*$");
 private final KubernetesClient kube; CatalogReconciler(KubernetesClient kube){this.kube=kube;}
 void reconcile(String ns,String catalogName,String baselineName,String userName,String key,Map<String,String> selectable,String defaultVersion){
  ConfigMap catalog=get(ns,catalogName), baseline=get(ns,baselineName), user=get(ns,userName); Set<String> refs=references(baseline,key,user); Map<String,String>d=new LinkedHashMap<>();d.put("defaultVersion",defaultVersion);selectable.forEach((v,i)->d.put("selectable."+v,i));
  for(String v:refs){String image=selectable.get(v);if(image==null){image=catalog.getData().get("retained."+v);if(image==null)throw new IllegalStateException("Referenced WireMock version is missing from the catalog: "+v);}if(!selectable.containsKey(v))d.put("retained."+v,image);}
  kube.configMaps().inNamespace(ns).resource(new ConfigMapBuilder(catalog).withData(d).build()).update();
 }
 private ConfigMap get(String ns,String name){ConfigMap c=kube.configMaps().inNamespace(ns).withName(name).get();if(c==null)throw new IllegalStateException("Required ConfigMap is missing: "+name);return c;}
 private Set<String> references(ConfigMap a,String key,ConfigMap b){java.util.Set<String>s=new java.util.TreeSet<>();for(ConfigMap c:java.util.List.of(a,b)){String y=c.getData().get(key);if(y!=null){Matcher m=VERSION.matcher(y);while(m.find())s.add(m.group(1));}}return s;}
}
