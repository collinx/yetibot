(ns yetibot.commands.jira
  (:require
    [clojure.tools.cli :refer [parse-opts]]
    [yetibot.observers.jira :refer [report-jira]]
    [yetibot.core.util :refer [filter-nil-vals map-to-strs]]
    [taoensso.timbre :refer [info debug warn error]]
    [clojure.string :refer [split join trim]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.data.json :as json]
    [yetibot.api.jira :as api
     :refer [jira-project-setting-key channel-projects]]))

(defn success? [res]
  (re-find #"^2" (str (:status res) "2")))

(defn format-error [{:keys [status body]}]
  (info "jira api error" status body)
  {:result/error
   (cond
     (= 403 status) (str "403 Forbidden. Verify your JIRA credentials?")
     (= 401 status) (str "401 Unauthorized. Check your JIRA credentials?")
     body (join " "
                (or
                  ;; try to figure out which one of JIRA's many weirdo
                  ;; responses we're dealing with here
                  (-> body :errorMessages seq)
                  (map
                    (fn [[k v]] (str (name k) ": " v))
                    (-> body :errors))))
     ;; ¯\_(ツ)_/¯
     :else (str status " JIRA API error"))})

(defn report-if-error
  "Checks the stauts of the HTTP response for 2xx, and if not, looks in the body
   for :errorMessages or :errors. To use this, make sure to use the
   `:throw-exceptions false`, `:content-type :json` and, `:coerce :always`
   options in the HTTP request."
  [req-fn succ-fn]
  (try
    (let [{:keys [body status] :as res} (req-fn)]
      ;; sometimes JIRA 200s even when there are errors so check if there are
      ;; errors in the json response
      (info "jira succ" status (pr-str body))
      (if (:errors body)
        (format-error res)
        (succ-fn res)))
    (catch Exception e
      (let [{:keys [status body] :as error} (ex-data e)
            json-body (try (json/read-str body :key-fn keyword)
                           (catch Exception e nil))]
        (debug "jira error" (pr-str e))
        (format-error (assoc error :body json-body))))))

(defn projects-cmd
  "jira projects # list configured projects (⭐️ indicates global default; ⚡️ indicates room default; room default overrides global default)"
  {:yb/cat #{:issue}}
  [{:keys [settings]}]
  (let [projects-for-chan (set (channel-projects settings))]
    (remove
      nil?
      (into
        (vec (for [pk (api/project-keys)]
               (when-not (projects-for-chan pk)
                 (str
                   (when (= pk (api/default-project-key)) "⭐️ ")
                   (api/url-from-key pk)))))
        (map (fn [pk] (str "⚡️ " (api/url-from-key pk)))
             projects-for-chan)))))

(defn users-cmd
  "jira users # list the users channel project or default project
   jira users <project> # list the users for the configured project(s)"
  {:yb/cat #{:issue}}
  [{:keys [settings] [_ project-key] :match}]
  (let [project (or project-key
                    (first (channel-projects settings))
                    (api/default-project-key))]
    (report-if-error
      #(api/get-users project)
      (fn [{:keys [body] :as res}]
        {:result/data body
         :result/value
         (into [(str "Users for project `" project "`")]
               (map :name body))}))))

(defn resolve-cmd
  "jira resolve <issue> <comment> # resolve an issue and set its resolution to fixed"
  {:yb/cat #{:issue}}
  [{[_ iss comment] :match user :user settings :settings}]
  (binding [api/*jira-projects* (channel-projects settings)]
    (let [comment (format "%s: %s" (:name user) comment)]
      (if-let [issue-data (:body (api/get-issue iss))]
        (report-if-error
          #(api/resolve-issue iss comment)
          (fn [res]
            (if res
              {:result/value (api/fetch-and-format-issue-short iss)
               :result/data res}
              {:result/error (str "Issue `" iss "` is already resolved")})))
        {:result/error (str "Unable to find any issue `" iss "`")}))))

(defn priorities-cmd
  "jira pri # list the priorities for this JIRA instance"
  {:yb/cat #{:issue}}
  [_]
  (report-if-error
    #(api/priorities)
    (fn [{priorities :body :as res}]
      {:result/value (->> priorities
                          (map (fn [{:keys [statusColor name description]}]
                                 [name (str description " " statusColor)]))
                          flatten
                          (apply sorted-map))
       :result/data priorities})))

(def issue-opts
  [["-j" "--project-key PROJECT KEY" "Project key"]
   ["-c" "--component COMPONENT" "Component"]
   ["-s" "--summary SUMMARY" "Summary"]
   ["-a" "--assignee ASSIGNEE" "Assignee"]
   ["-f" "--fix-version FIX VERSION" "Fix version"]
   ["-d" "--desc DESCRIPTION" "Description"]
   ["-t" "--time TIME ESTIAMTED" "Time estimated"]
   ["-r" "--remaining REMAINING TIME ESTIAMTED" "Remaining time estimated"]
   ["-p" "--parent PARENT ISSUE KEY" "Parent issue key; creates a sub-task if specified"]])

(defn parse-issue-opts
  "Parse opts using issue-opts and trim all the values of the keys in options"
  [opts]
  (let [parsed (parse-opts (map trim (split opts #"(?=\s-\w)|(?<=\s-\w)")) issue-opts)]
    (update-in parsed [:options]
               (fn [options]
                 (into {} (map (fn [[k v]] [k (trim v)]) options))))))

(defn create-cmd
  "jira create <summary> [-c <component>] [-j project-key] [-a <assignee>] [-d <description>] [-f <fix-version>] [-t <time estimated>] [-p <parent-issue-key> (creates a sub-task if specified)]"
  {:yb/cat #{:issue}}
  [{[_ opts-str] :match settings :settings}]
  (binding [api/*jira-projects* (channel-projects settings)]
    (let [parsed (parse-issue-opts opts-str)
          summary (->> parsed :arguments (join " "))
          opts (:options parsed)
          component-ids (when (:component opts)
                          (map :id
                               (api/find-component-like (:component opts))))]
      (report-if-error
        #(api/create-issue
          (filter-nil-vals
            (merge
              {:summary summary}
              (when component-ids {:component-ids component-ids})
              (select-keys opts [:fix-version :project-key :parent
                                 :desc :assignee])
              (when (:time opts)
                {:timetracking {:originalEstimate (:time opts)
                                :remainingEstimate (:time opts)}}))))
        (fn [res]
          (info "create command" (pr-str res))
          (let [iss-key (-> res :body :key)]
            {:result/value (api/fetch-and-format-issue-short iss-key)
             :result/data (select-keys
                            res [:body :status :request-time])}))))))

(defn update-cmd
  "jira update <issue-key> [-s <summary>] [-c <component>] [-a <assignee>] [-d <description>] [-f <fix-version>] [-t <time estimated>] [-r <remaining time estimated>]"
  {:yb/cat #{:issue}}
  [{[_ issue-key opts-str] :match settings :settings}]
  (binding [api/*jira-projects* (channel-projects settings)]
    (let [parsed (parse-issue-opts opts-str)
          opts (:options parsed)]
      (clojure.pprint/pprint parsed)
      (let [component-ids (when (:component opts)
                            (map :id (api/find-component-like
                                       (:component opts))))]

          (report-if-error
            #(api/update-issue
              issue-key
              (filter-nil-vals
                (merge
                  {:component-ids component-ids}
                  (select-keys opts [:fix-version :summary :desc :assignee])
                  (when (or (:remaining opts) (:time opts))
                    {:timetracking
                     (merge (when (:remaining opts)
                              {:remainingEstimate (:remaining opts)})
                            (when (:time opts)
                              {:originalEstimate (:time opts)}))}))))

            (fn [res]
              (info "updated" res)
              (let [iss-key (-> res :body :key)]
                {:result/value (str "Updated: "
                                    (api/fetch-and-format-issue-short
                                      issue-key))
                 :result/data (:body res)})))))))

(defn- short-jira-list [res]
  (if (success? res)
    (map api/format-issue-short
         (->> res :body :issues (take 15)))
    (-> res :body :errorMessages)))

(defn assign-cmd
  "jira assign <issue> <assignee> # assign <issue> to <assignee>"
  {:yb/cat #{:issue}}
  [{[_ iss-key assignee] :match settings :settings}]
  (binding [api/*jira-project* (settings jira-project-setting-key)]
    (report-if-error
      #(api/assign-issue iss-key assignee)
      (fn [res]
        (if (res)
          {:result/value (report-jira iss-key)
           :result/data (:body res)}
          {:result/error (format "Unable to assign %s to %s"
                                 iss-key assignee)})))))

(defn comment-cmd
  "jira comment <issue> <comment> # comment on <issue>"
  {:yb/cat #{:issue}}
  [{[_ iss-key body] :match user :user settings :settings}]
  (binding [api/*jira-project* (settings jira-project-setting-key)]
    (let [body (format "%s: %s" (:name user) body)]
      (report-if-error
        #(api/post-comment iss-key body)
        (fn [res]
          (report-jira iss-key)
          {:result/value (str "Successfully commented on " iss-key)
           :result/data (:body res)})))))

(defn recent-cmd
  "jira recent # show the 15 most recent issues"
  {:yb/cat #{:issue}}
  [{:keys [settings]}]
  (binding [api/*jira-projects* (channel-projects settings)]
    (report-if-error
      #(api/recent)
      (fn [res]
        {:result/value (short-jira-list res)
         :result/data (-> res :body :issues)}))))

(defn search-cmd
  "jira search <query> # return up to 15 issues matching <query> across all configured projects"
  {:yb/cat #{:issue}}
  [{[_ query] :match settings :settings}]
  (binding [api/*jira-projects* (channel-projects settings)]
    (report-if-error
      #(api/search-by-query query)
      (fn [res]
        {:result/value (short-jira-list res)
         :result/data res}))))

(defn jql-cmd
  "jira jql <jql> # return up to 15 issues matching <jql> query across all configured projects"
  {:yb/cat #{:issue}}
  [{[_ jql] :match settings :settings}]
  (binding [api/*jira-projects* (channel-projects settings)]
    (report-if-error
      #(api/search jql)
      (fn [res]
        {:result/value (short-jira-list res)
         :result/data (-> res :body :issues)}))))

(defn components-cmd
  "jira components # list components and their leads by project"
  {:yb/cat #{:issue}}
  [{:keys [settings]}]
  (binding [api/*jira-projects* (channel-projects settings)]
    ;; TODO this needs error handling but our current err handling structure
    ;; doesn't work so well for composite results from multiple API calls 🤔
    ;; unless we figured out a way to report multiple results
    (let [data (mapcat (comp :body api/components) (api/project-keys))]
      {:result/data data
       :result/value (map
                       (fn [{component-name :name
                             :keys [project lead description]}]
                         (str
                           "[" project "] "
                           "[" (-> lead :name) "] "
                           component-name
                           " — "
                           description))
                       data)})))

(defn format-version [v]
  (str (:name v)
       (when-let [rd (:releaseDate v)] (str " [release date " rd "]"))
       (when (:archived v) " [archived]")
       (when (:released v) " [released]")))

(defn versions-cmd
  "jira versions [<project-key>] # list versions for <project-key>. Lists versions for all configured project-keys if not specified."
  {:yb/cat #{:issue}}
  [{[_ project-key] :match settings :settings}]
  (binding [api/*jira-projects* (channel-projects settings)]
    (let [project-keys (if project-key [project-key] (api/project-keys))
          ;; TODO needs error handling
          data (mapcat
                 #(->> (api/versions %)
                       :body
                       (map (fn [v] (assoc v :project %))))
                 project-keys)]
      {:result/data data
       :result/value (map (fn [version]
                            (str "[" (:project version) "] "
                                 (format-version version)))
                          data)})))

(defn parse-cmd
  "jira parse <text> # parse the issue key out of a jira issue URL"
  {:yb/cat #{:issue}}
  [{[_ text] :match}]
  (second (re-find #"browse\/([^\/]+)" text)))

(defn show-cmd
  "jira show <issue> # show the full details of an issue"
  {:yb/cat #{:issue}}
  [{[_ issue-key] :match}]
  (report-if-error
    #(api/get-issue issue-key)
    (fn [{:keys [body]}]
      (info "!!! show-cmd" (pr-str body))
      {:result/value (api/format-issue-long body)
       :result/data body})))

(defn delete-cmd
  "jira delete <issue> # delete the issue"
  {:yb/cat #{:issue}}
  [{[_ issue-key] :match}]
  (report-if-error
    #(api/delete-issue issue-key)
    (fn [res]
      (info "deleted jira issue" issue-key res)
      {:result/value (str "Deleted " issue-key)
       :result/data (:body res)})))

(cmd-hook #"jira"
  #"^projects" projects-cmd
  #"^parse\s+(.+)" parse-cmd
  #"^show\s+(\S+)" show-cmd
  #"^delete\s+(\S+)" delete-cmd
  #"^components" components-cmd
  #"^versions\s*(\S+)*" versions-cmd
  #"^recent" recent-cmd
  #"^pri" priorities-cmd
  #"^users\s*(\S.+)*" users-cmd
  #"^assign\s+(\S+)\s+(\S+)" assign-cmd
  #"^comment\s+(\S+)\s+(.+)" comment-cmd
  #"^search\s+(.+)" search-cmd
  #"^jql\s+(.+)" jql-cmd
  #"^create\s+(.+)" create-cmd
  #"^update\s+(\S+)\s+(.+)" update-cmd
  #"^resolve\s+([\w\-]+)\s+(.+)" resolve-cmd)
