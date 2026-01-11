package io.featurehub.examples.springboot;


import io.featurehub.client.ClientContext;
import io.featurehub.client.FeatureHubConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController()
@RequestMapping("/todo")
public class TodoResource {
  private static final Logger log = LoggerFactory.getLogger(TodoResource.class);
  private final FeatureHubConfig featureHub;
  Map<String, Map<String, Todo>> todos = new ConcurrentHashMap<>();

  @Autowired
  public TodoResource(FeatureHubConfig config) {
    this.featureHub = config;
    log.info("created");
  }

  private Map<String, Todo> getTodoMap(String user) {
    return todos.computeIfAbsent(user, (key) -> new ConcurrentHashMap<>());
  }

  // ideally we wouldn't do it this way, but this is the API, the user is in the url
  // rather than in the Authorisation token. If it was in the token we would do the context
  // creation in a filter and inject the context instead
  private List<Todo> getTodoList(Map<String, Todo> todos, String user) {
    ClientContext fhClient = fhClient(user);

    final List<Todo> todoList = todos.values().stream().map(t -> t.copy().title(processTitle(fhClient, t.getTitle()))).collect(Collectors.toList());
    return todoList;
  }

  private String processTitle(ClientContext fhClient, String title) {
    if (title == null) {
      return null;
    }

    if (fhClient == null) {
      return title;
    }

    if (fhClient.isSet("FEATURE_STRING") && "buy".equals(title)) {
      title = title + " " + fhClient.feature("FEATURE_STRING").getString();
      log.debug("Processes string feature: {}", title);
    }

    if (fhClient.isSet("FEATURE_NUMBER") && title.equals("pay")) {
      title =  title + " " + fhClient.feature("FEATURE_NUMBER").getNumber().toString();
      log.debug("Processed number feature {}", title);
    }

    if (fhClient.isSet("FEATURE_JSON") && title.equals("find")) {
      final Map feature_json = fhClient.feature("FEATURE_JSON").getJson(Map.class);
      title = title + " " + feature_json.get("foo").toString();
      log.debug("Processed JSON feature {}", title);
    }

    if (fhClient.isEnabled("FEATURE_TITLE_TO_UPPERCASE")) {
      title = title.toUpperCase();
      log.debug("Processed boolean feature {}", title);
    }

    return title;
  }

  @NotNull
  private ClientContext fhClient(String user) {
    try {
      final ClientContext context = featureHub.newContext()
        .userKey(user)
        .attrs("mine", List.of("yours", "his"))
        .build().get();

//      FeatureHubClientContextThreadLocal.set(context);
//
//      if (featureHub.segmentAnalytics() != null) {
//        // this should have the current user's details augmented into it
//        featureHub.segmentAnalytics().getAnalytics().enqueue(IdentifyMessage.builder().userId(user));
//      }

      context.feature("SUBMIT_COLOR_BUTTON").isSet();

      return context;
    } catch (Exception e) {
      log.error("Unable to get context!", e);
      throw new ResponseStatusException(HttpStatusCode.valueOf(503), "Not connected");
    }
  }

  @PostMapping(value = "/{user}", consumes = "application/json", produces = "application/json")
  public List<Todo> addTodo(@NotNull @PathVariable("user") String user, @RequestBody Todo body) {
    if (body.getId() == null || body.getId().isEmpty()) {
      body.id(UUID.randomUUID().toString());
    }

    if (body.getResolved() == null) {
      body.resolved(false);
    }

    Map<String, Todo> userTodo = getTodoMap(user);
    userTodo.put(body.getId(), body);

    return getTodoList(userTodo, user);
  }

  @GetMapping(value = "/{user}", produces = "application/json")
  public List<Todo> listTodos(@NotNull @PathVariable("user") String user) {
    return getTodoList(getTodoMap(user), user);
  }

  @DeleteMapping(value = "/{user}", produces = "application/json")
  public void removeAllTodos(@NotNull @PathVariable("user") String user) {
    getTodoMap(user).clear();
  }

  @GetMapping(value = "/{user}/{id}", produces = "application/json")
  public List<Todo> removeTodo(@NotNull @PathVariable("user") String user, @NotNull @PathVariable("id") String id) {
    Map<String, Todo> userTodo = getTodoMap(user);
    userTodo.remove(id);
    return getTodoList(userTodo, user);
  }

  @PutMapping(value = "/{user}/{id}", produces = "application/json")
  public List<Todo> resolveTodo(@NotNull @PathVariable("user") String user, @NotNull @PathVariable("id") String id) {
    Map<String, Todo> userTodo = getTodoMap(user);

    Todo todo = userTodo.get(id);

    if (todo == null) {
      throw new ResponseStatusException(HttpStatusCode.valueOf(404), "No such todo");
    }

    todo.setResolved(true);

    return getTodoList(userTodo, user);
  }
}
