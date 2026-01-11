package io.featurehub.examples.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import todo.model.Todo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TodoSource {
  public Map<String, Map<String, Todo>> todos = new ConcurrentHashMap<>();

  public Todo copy(Todo t) {
    return new Todo().resolved(t.getResolved()).id(t.getId()).title(t.getTitle()).when(t.getWhen());
  }
}
