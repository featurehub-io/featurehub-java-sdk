package todo.backend.resources;

import io.featurehub.client.ClientContext;
import io.featurehub.client.ThreadLocalContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import todo.api.TodoService;
import todo.backend.FeatureHub;
import todo.model.Todo;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


public class TodoResource implements TodoService {
  private static final Logger log = LoggerFactory.getLogger(TodoResource.class);
  private final FeatureHub config;
  Map<String, Map<String, Todo>> todos = new ConcurrentHashMap<>();

  @Inject
  public TodoResource(FeatureHub config) {
    this.config = config;
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

    return todos.values().stream().map(t -> t.copy().title(processTitle(fhClient, t.getTitle()))).collect(Collectors.toList());
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

  @NotNull private ClientContext fhClient(String user) {
    try {
      return config.getConfig().newContext().userKey(user).build().get();
    } catch (Exception e) {
      log.error("Unable to get context!", e);
      throw new WebApplicationException(e);
    }
  }

  @Override
  public List<Todo> addTodo(@NotNull String user, Todo body) {
    if (body.getId().isEmpty()) {
      body.id(UUID.randomUUID().toString());
    }

    Map<String, Todo> userTodo = getTodoMap(user);
    userTodo.put(body.getId(), body);

    return getTodoList(userTodo, user);
  }

  @Override
  public List<Todo> listTodos(@NotNull String user) {
    return getTodoList(getTodoMap(user), user);
  }

  @Override
  public void removeAllTodos(@NotNull String user) {
    getTodoMap(user).clear();
  }

  @Override
  public List<Todo> removeTodo(@NotNull String user, @NotNull String id) {
    Map<String, Todo> userTodo = getTodoMap(user);
    userTodo.remove(id);
    return getTodoList(userTodo, user);
  }

  @Override
  public List<Todo> resolveTodo(@NotNull String id, @NotNull String user) {
    Map<String, Todo> userTodo = getTodoMap(user);

    Todo todo = userTodo.get(id);

    if (todo == null) {
      throw new NotFoundException();
    }

    todo.setResolved(true);

    return getTodoList(userTodo, user);
  }
}
