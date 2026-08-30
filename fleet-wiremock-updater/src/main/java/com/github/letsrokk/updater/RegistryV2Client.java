package com.github.letsrokk.updater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class RegistryV2Client {
    private static final int MAX_PAGES = 50, MAX_TAGS = 5000;
    private final HttpClient client; private final ObjectMapper json; private final Optional<Credentials> credentials;
    RegistryV2Client(HttpClient client, ObjectMapper json, Credentials credentials) { this.client=client; this.json=json; this.credentials=Optional.ofNullable(credentials); }
    List<String> tags(URI registry, String repository, int pageSize) {
        if (pageSize < 1 || pageSize > 1000) throw new IllegalArgumentException("page size must be from 1 to 1000.");
        URI next=registry.resolve("/v2/"+repository+"/tags/list?n="+pageSize); Set<String> tags=new LinkedHashSet<>(); String bearer=null;
        for(int page=0; next!=null; page++) { if(page>=MAX_PAGES) throw new IllegalStateException("Registry tag page limit exceeded.");
            HttpResponse<String> response=send(next, bearer);
            if(response.statusCode()==401 && bearer==null) { bearer=token(response, repository); response=send(next,bearer); }
            if(response.statusCode()!=200) throw new IllegalStateException("Registry tag request failed: HTTP "+response.statusCode());
            try { JsonNode values=json.readTree(response.body()).path("tags"); if(!values.isArray()) throw new IllegalStateException("Registry tag response lacks tags."); for(JsonNode value:values) { if(!value.isTextual()||!tags.add(value.textValue())||tags.size()>MAX_TAGS) { if(tags.size()>MAX_TAGS) throw new IllegalStateException("Registry tag limit exceeded."); } } } catch(Exception e) { if(e instanceof IllegalStateException x) throw x; throw new IllegalStateException("Invalid registry tag response.",e); }
            next=next(response.headers().firstValue("Link"),next);
        } return List.copyOf(tags);
    }
    private HttpResponse<String> send(URI uri,String bearer) { try { HttpRequest.Builder b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET(); if(bearer!=null)b.header("Authorization","Bearer "+bearer); else credentials.ifPresent(c->b.header("Authorization",c.basic())); return client.send(b.build(),HttpResponse.BodyHandlers.ofString()); } catch(Exception e){throw new IllegalStateException("Registry request failed.",e);} }
    private String token(HttpResponse<String> response,String repository) { String h=response.headers().firstValue("WWW-Authenticate").orElseThrow(()->new IllegalStateException("Registry did not provide a Bearer challenge.")); if(!h.regionMatches(true,0,"Bearer ",0,7))throw new IllegalStateException("Unsupported registry authentication challenge."); java.util.Map<String,String> p=new java.util.HashMap<>(); java.util.regex.Matcher m=java.util.regex.Pattern.compile("(\\w+)=\\\"([^\\\"]*)\\\"").matcher(h); while(m.find())p.put(m.group(1),m.group(2)); String realm=p.get("realm"),service=p.get("service"); if(realm==null)throw new IllegalStateException("Registry Bearer challenge lacks realm."); String query="service="+enc(service)+"&scope="+enc(p.getOrDefault("scope","repository:"+repository+":pull")); HttpResponse<String> r=sendToken(URI.create(realm+(realm.contains("?")?"&":"?")+query)); if(r.statusCode()!=200)throw new IllegalStateException("Registry token request failed: HTTP "+r.statusCode()); try { JsonNode n=json.readTree(r.body()); String t=n.path("token").asText(n.path("access_token").asText()); if(t.isBlank())throw new IllegalStateException("Registry token response lacks token."); return t;}catch(Exception e){if(e instanceof IllegalStateException x)throw x;throw new IllegalStateException("Invalid registry token response.",e);} }
    private HttpResponse<String> sendToken(URI u){try{HttpRequest.Builder b=HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(20)).GET();credentials.ifPresent(c->b.header("Authorization",c.basic()));return client.send(b.build(),HttpResponse.BodyHandlers.ofString());}catch(Exception e){throw new IllegalStateException("Registry token request failed.",e);}}
    private static URI next(Optional<String> link,URI current){if(link.isEmpty())return null; java.util.regex.Matcher m=java.util.regex.Pattern.compile("<([^>]+)>;\\s*rel=\\\"?next\\\"?").matcher(link.get());return m.find()?current.resolve(m.group(1)):null;} private static String enc(String v){return URLEncoder.encode(v==null?"":v,StandardCharsets.UTF_8);} record Credentials(String username,String password){String basic(){return "Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));}}
}
