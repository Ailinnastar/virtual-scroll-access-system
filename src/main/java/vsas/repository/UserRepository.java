package vsas.repository;

import java.util.List;
import java.util.Optional;
import vsas.model.User;

// TODO DATA-01: implement durable storage, unique ID keys and safe profile updates.
public interface UserRepository {
    Optional<User> findById(String idKey);
    Optional<User> findByUsername(String username);
    List<User> findAll();
    void save(User user);
    void delete(String idKey);
}
