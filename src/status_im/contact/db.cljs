(ns status-im.contact.db
  (:require [cljs.spec.alpha :as spec]
            [status-im.js-dependencies :as js-dependencies]
            [status-im.utils.identicon :as identicon]
            [status-im.utils.gfycat.core :as gfycat]
            [status-im.utils.ethereum.core :as ethereum]
            status-im.utils.db))

;;;; DB

;;Contact

(spec/def :contact/public-key :global/not-empty-string)
(spec/def :contact/name :global/not-empty-string)
(spec/def :contact/address (spec/nilable :global/address))
(spec/def :contact/photo-path (spec/nilable string?))
(spec/def :contact/fcm-token (spec/nilable string?))
(spec/def :contact/last-updated (spec/nilable int?))
(spec/def :contact/last-online (spec/nilable int?))

(spec/def :contact/tags (spec/coll-of string? :kind set?))
;; contact/blocked: the user is blocked
;; contact/added: the user was added to the contacts and a contact request was sent
(spec/def :contact/system-tags (spec/coll-of keyword? :kind set?))

(spec/def :contact/contact (spec/keys  :req-un [:contact/public-key
                                                :contact/name]
                                       :opt-un [:contact/address
                                                :contact/photo-path
                                                :contact/last-updated
                                                :contact/last-online
                                                :contact/fcm-token
                                                :contact/tags
                                                :contact/system-tags]))

;;Contact list ui props
(spec/def :contact-list-ui/edit? boolean?)

;;Contacts ui props
(spec/def :contacts-ui/edit? boolean?)

(spec/def :contacts/contacts (spec/nilable (spec/map-of :global/not-empty-string :contact/contact)))
;;public key of new contact during adding this new contact
(spec/def :contacts/new-identity (spec/nilable string?))
(spec/def :contacts/new-identity-error (spec/nilable string?))
;;on showing this contact's profile (andrey: better to move into profile ns)
(spec/def :contacts/identity (spec/nilable :global/not-empty-string))
(spec/def :contacts/list-ui-props (spec/nilable (spec/keys :opt-un [:contact-list-ui/edit?])))
(spec/def :contacts/ui-props (spec/nilable (spec/keys :opt-un [:contacts-ui/edit?])))
;;used in modal list (for example for wallet)
(spec/def :contacts/click-handler (spec/nilable fn?))
;;used in modal list (for example for wallet)
(spec/def :contacts/click-action (spec/nilable #{:send :request}))
;;used in modal list (for example for wallet)
(spec/def :contacts/click-params (spec/nilable map?))

(spec/def :contact/new-tag string?)
(spec/def :ui/contact (spec/keys :opt [:contact/new-tag]))

(defn public-key->address [public-key]
  (let [length (count public-key)
        normalized-key (case length
                         132 (subs public-key 4)
                         130 (subs public-key 2)
                         128 public-key
                         nil)]
    (when normalized-key
      (subs (.sha3 js-dependencies/Web3.prototype normalized-key #js {:encoding "hex"}) 26))))

(defn public-key->new-contact [public-key]
  {:name        (gfycat/generate-gfy public-key)
   :address     (public-key->address public-key)
   :photo-path  (identicon/identicon public-key)
   :public-key  public-key
   :system-tags #{}})

(defn public-key->contact
  [contacts public-key]
  (when public-key
    (get contacts public-key
         (public-key->new-contact public-key))))

(defn- contact-by-address [[_ contact] address]
  (when (ethereum/address= (:address contact) address)
    contact))

(defn find-contact-by-address [contacts address]
  (some #(contact-by-address % address) contacts))

(defn sort-contacts
  [contacts]
  (sort (fn [c1 c2]
          (let [name1 (or (:name c1) (:address c1) (:public-key c1))
                name2 (or (:name c2) (:address c2) (:public-key c2))]
            (compare (clojure.string/lower-case name1)
                     (clojure.string/lower-case name2))))
        (vals contacts)))

(defn filter-dapps
  [v dev-mode?]
  (remove #(when-not dev-mode? (true? (:developer? %))) v))

(defn filter-group-contacts
  [group-contacts contacts]
  (let [group-contacts' (into #{} group-contacts)]
    (filter #(group-contacts' (:public-key %)) contacts)))

(defn query-chat-contacts
  [{:keys [contacts]} all-contacts query-fn]
  (let [participant-set (into #{} (filter identity) contacts)]
    (query-fn (comp participant-set :public-key) (vals all-contacts))))

(defn get-all-contacts-in-group-chat
  [members admins contacts current-account]
  (let [{:keys [public-key] :as current-account-contact}
        (select-keys current-account [:name :photo-path :public-key])
        all-contacts (assoc contacts public-key current-account-contact)]
    (->> members
         (map #(or (get all-contacts %)
                   (public-key->new-contact %)))
         (sort-by (comp clojure.string/lower-case :name))
         (map #(if (admins (:public-key %))
                 (assoc % :admin? true)
                 %)))))

(defn added?
  ([{:keys [system-tags]}]
   (contains? system-tags :contact/added))
  ([db public-key]
   (added? (get-in db [:contacts/contacts public-key]))))

(defn active?
  ([{:keys [system-tags]}]
   (and (contains? system-tags :contact/added)
        (not (contains? system-tags :contact/blocked))))
  ([db public-key]
   (active? (get-in db [:contacts/contacts public-key]))))

(defn blocked?
  ([{:keys [system-tags]}]
   (contains? system-tags :contact/blocked))
  ([db public-key]
   (blocked? (get-in db [:contacts/contacts public-key]))))

(defn enrich-contact
  [{:keys [system-tags] :as contact}]
  (assoc contact
         :blocked? (blocked? contact)
         :active? (active? contact)
         :added? (contains? system-tags :contact/added)))

(defn enrich-contacts
  [contacts]
  (reduce (fn [acc [public-key contact]]
            (assoc acc public-key (enrich-contact contact)))
          {}
          contacts))

(defn get-blocked-contacts
  [contacts]
  (reduce (fn [acc {:keys [public-key] :as contact}]
            (if (blocked? contact)
              (conj acc public-key)
              acc))
          #{}
          contacts))

(defn get-active-contacts
  [contacts]
  (->> contacts
       (filter (fn [[_ contact]]
                 (active? contact)))
       sort-contacts))
