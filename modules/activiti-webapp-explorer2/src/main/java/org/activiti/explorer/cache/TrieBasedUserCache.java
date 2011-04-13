/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.explorer.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.activiti.engine.IdentityService;
import org.activiti.engine.identity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;



/**
 * Simple cache of user information, to avoid hitting the database too often for 
 * information that doesn't change much over time.
 * 
 * Based on a Trie datastructure (http://en.wikipedia.org/wiki/Trie), see {@link RadixTree},
 * for fast 'telephonebook'-like retrieval based on the first and last name of the users.
 * Note that we are using the Trie such that we can have multiple results for a given key, by
 * giving each key a list of matching values:
 * eg. key='kermit' has a list of values {Kermit The Frog, Kermit The Evil Overlord, ...} 
 * 
 * TODO: In a clustered/cloud environment, this cache must be refreshed each xx minutes,
 * in case updates have been done on other machines. Alternatively, a solution
 * such as memcached could replace this implementation later on.
 * 
 * @author Joram Barrez
 */
@Component
public class TrieBasedUserCache implements UserCache {
  
  private static final Logger LOGGER = Logger.getLogger(TrieBasedUserCache.class.getName());
  
  protected IdentityService identityService;
  protected RadixTree<List<User>> userTrie = new RadixTreeImpl<List<User>>();
  protected Map<String, User> userCache = new HashMap<String, User>();
  
  public void refresh() {
    userTrie = new RadixTreeImpl<List<User>>();
    loadUsers();
  }
  
  public synchronized void loadUsers() {
    long nrOfUsers = identityService.createUserQuery().count();
    long usersAdded = 0;
    
    userTrie = new RadixTreeImpl<List<User>>();
    userCache = new HashMap<String, User>();
    
    while (usersAdded < nrOfUsers) {

      if (LOGGER.isLoggable(Level.INFO)) {
        LOGGER.info("Caching users " + usersAdded + " to " + (usersAdded+25));
      }
      
      List<User> users = identityService.createUserQuery().listPage((int) usersAdded, 25);
      for (User user : users) {
        String firstName = user.getFirstName();
        String lastName = user.getLastName();
        
        // firstname for dictionary lookup
        if (firstName != null && !"".equals(firstName)) {
          for (String firstNameToken : user.getFirstName().split(" ")) {
            addTrieCacheItem(firstNameToken, user);
          }
        }
        
        // lastname for dictionary lookup
        if (lastName != null && !"".equals(lastName)) {
          for (String lastNameToken : user.getLastName().split(" ")) {
            addTrieCacheItem(lastNameToken, user);
          }
        }
        
        // User cache
        addUserCacheItem(user);
        
        usersAdded++;
      }
    }
  }
  
  protected void addTrieCacheItem(String key, User user) {
    key = key.toLowerCase();
    List<User> value = null;
    if (!userTrie.contains(key)) {
      value = new ArrayList<User>();
    } else {
      value = userTrie.find(key);
    }
    
    value.add(user);
    userTrie.delete(key);
    userTrie.insert(key, value);
  }
  
  protected void addUserCacheItem(User user) {
    userCache.put(user.getId(), user);
  }
  
  public User findUser(String userId) {
    if (userCache.isEmpty()) {
      loadUsers();
    }
    return userCache.get(userId);
  }
  
  public List<User> findMatchingUsers(String prefix) {
    
    if (userTrie.getSize() == 0) {
      refresh();
    }
    
    List<User> returnValue = new ArrayList<User>();
    List<List<User>> results = userTrie.searchPrefix(prefix.toLowerCase(), 100); // 100 should be enough for any name
    for (List<User> result : results) {
      for (User userDetail : result) {
        returnValue.add(userDetail);
      }
    }
    return returnValue;
  }

  @Autowired
  public void setIdentityService(IdentityService identityService) {
    this.identityService = identityService;
    loadUsers();
  }
  
}
