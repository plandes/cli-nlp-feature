(ns ^{:doc "Character feature creation functions."
      :author "Paul Landes"}
    zensols.nlparse.feature.char
  (:import com.zensols.util.StringUtils)
  (:require [clojure.string :as s]
            [clojure.set :refer (union)]
            [clojure.tools.logging :as log])
  (:require [clojure.core.matrix.stats :as stat])
  (:require [zensols.nlparse.locale :as lc]
            [zensols.nlparse.feature.util :refer :all]))

;; (longest) repeating characters
(defn- lrs-unique-feature-metas [unique-idx]
  [[(keyword (format "lrs-occurs-%d" unique-idx)) 'numeric]
   [(keyword (format "lrs-occurs-ratio-%d" unique-idx)) 'numeric]
   [(keyword (format "lrs-length-%d" unique-idx)) 'numeric]])

(defn lrs-feature-metas
  "See [[lrs-features]]."
  [count]
  (concat [[:lrs-len 'numeric]
           [:lrs-unique-chars 'numeric]]
          (->> (range 1 (inc count))
               (map lrs-unique-feature-metas)
               (apply concat))))

(defn lrs-features
  "Return the following features:

  * **:lrs-len** longest repeating string length
  * **:lrs-unique-characters** the number of unique characters in the longest
  repeating string
  * **:lrs-occurs-N** the number of times the string repeated that has N unique
  consecutive characters
  * **:lrs-length-N** the length of the string that has N unique consecutive
  characters

  All where `N` is **unique-char-repeats**, which is a range from 1 to `N` of
  the grouping of consecutive characters.  For example the string:

```
          1         2         3         4         5
01234567890123456789012345678901234567890123456789012
abcabc aabb aaaaaa abcabcabcabc abcdefgabcdefgabcdefg
```

  yields:

```
{:lrs-len 14,           ; abcdefgabcdefgabcdefg (TODO: should be 21)
 :lrs-unique-chars 7,   ; abcdefg
 :lrs-length-1 1,       ; 'a'
 :lrs-occurs-1 6,       ; 'aaaaaa' at index 12
 :lrs-length-2 3,       ; ' aa'
 :lrs-occurs-2 1,       ; index: 7
 :lrs-length-3 3,       ; 'abcabc'
 :lrs-occurs-3 4,       ; indexes: 0, 19, 25
 :lrs-length-4 4,       ; ' abc'
 :lrs-occurs-4 1,
 :lrs-length-5 5,       ; 'cdefg' (has to be consecutive/non-overlapping)
 :lrs-occurs-5 1,
 :lrs-length-6 6,       ; 'bcdefg'
 :lrs-occurs-6 1,
 :lrs-length-7 7,       ; 'abcdefg'
 :lrs-occurs-7 3}       ; indexes: 32, 39, 49
```"
  [text unique-char-repeats]
  (let [text (s/replace text #"\s+" " ")
        reps (->> (StringUtils/longestRepeatedString text)
                  (map (fn [rs]
                         {:str rs
                          :length (count rs)
                          :occurs (StringUtils/countConsecutiveOccurs rs text)
                          :unique (count (StringUtils/uniqueChars rs))}))
                  (#(if (empty? %)
                      [{:str "" :length -1 :occurs -1 :unique -1}]
                      %))
                  (sort (fn [a b]
                          (compare (:occurs b) (:occurs a)))))
        lrs-features (->> reps
                          (sort (fn [a b]
                                  (compare (:length b) (:length a))))
                          (take 1)
                          (map (fn [{:keys [length unique]}]
                                 {:lrs-len length
                                  :lrs-unique-chars unique}))
                          first)
        rng (range 1 (inc unique-char-repeats))
        text-len (count text)]
    (->> rng
         (map (fn [ucr]
                (first (filter #(-> % :unique (= ucr)) reps))))
         (map (fn [cnt rep]
                (or rep {:unique cnt}))
              rng)
         (map (fn [{:keys [length occurs unique]}]
                (zipmap (map first (lrs-unique-feature-metas unique))
                        [(or occurs -1)
                         (if occurs
                           (/ occurs text-len)
                           -1)
                         (or length -1)])))
         (apply merge lrs-features))))

;; character distribution
(defn char-dist-feature-metas
  "See [[char-dist-features]]."
  []
  [[:char-dist-unique 'numeric]
   [:char-dist-unique-ratio 'numeric]
   [:char-dist-variance 'numeric]
   [:char-dist-mean 'numeric]
   [:char-dist-count 'numeric]])

(defn char-dist-features
  "Return the following features generated from **text**:

  * **:char-dist-unique** number of unique characters
  * **:char-dist-unique-ratio** ratio of unique characters to non-unique
  * **:char-dist-count** character length
  * **:char-dist-variance** variance of character counts
  * **:char-dist-mean** mean of character counts"
  [text]
  (let [char-dist (->> (StringUtils/uniqueCharCounts text) vals)
        len (count text)]
   {:char-dist-unique (count char-dist)
    :char-dist-unique-ratio (ratio-neg-if-empty (count char-dist) len)
    :char-dist-count len
    :char-dist-variance (if (<= (count char-dist) 1)
                          -1 (->> char-dist stat/variance))
    :char-dist-mean (if (= len 0) -1 (->> char-dist stat/mean))}))

;; punctuation
(def punctuation
  "Natural language punctuation and several languages."
  (set ".!¿?,:;"))

(def latin-non-alpha-numeric
  "Latin character set but not alpha numeric"
  (union punctuation (set "~@#$%^&*(){}[]<>|\\/-+_")))

(defn punctuation-features
  "Return the following features from **text**:

   * **:punctuation-count** the count of [[punctuation]]
   * **:punctuation-ratio** the ratio of count of [[punctuation]] to the length
   * **:latin-non-alpha-numeric-count** like **:punctuation-count**but with [[latin-non-alpha-numeric]]
   * **:latin-non-alpha-numeric-ratio** like **:punctuation-ratio**but with [[latin-non-alpha-numeric]]"
  [text]
  (let [feats (reduce (fn [{:keys [punc latin-nan question explanation]} char]
                        {:punc (+ punc (if (contains? punctuation char) 1 0))
                         :latin-nan (+ latin-nan
                                       (if (contains? latin-non-alpha-numeric char)
                                         1 0))
                         :question (+ question (if (= char \?) 1 0))
                         :explanation (+ explanation (if (= char \!) 1 0))})
                      {:punc 0 :latin-nan 0 :question 0 :explanation 0}
                      text)
        {:keys [punc latin-nan question explanation]} feats
        len (count text)]
    {:punctuation-count punc
     :punctuation-ratio (ratio-neg-if-empty punc len)
     :question-count question
     :question-ratio (ratio-neg-if-empty question len)
     :explanation-count explanation
     :explanation-ratio (ratio-neg-if-empty explanation len)
     :latin-non-alpha-numeric-count (:latin-nan feats)
     :latin-non-alpha-numeric-ratio (ratio-neg-if-empty (:latin-nan feats) len)}))

(defn punctuation-metas
  "See [[punctuation-features]]."
  []
  [[:punctuation-count 'numeric]
   [:punctuation-ratio 'numeric]
   [:question-ratio 'numeric]
   [:question-count 'numeric]
   [:explanation-ratio 'numeric]
   [:explanation-count 'numeric]
   [:latin-non-alpha-numeric-count 'numeric]
   [:latin-non-alpha-numeric-ratio 'numeric]])



;; unicode
(defn- unicode-variance [text ucounts]
 (let [total (count text)]
   (->> ucounts
        (map :count)
        (#(conj % (- total (reduce + %))))
        (#(if (<= (count %) 1) 0 (stat/variance %))))))

(defn- unicode-range-feature-keys [index]
  (let [lkeys (conj (lc/locale-keys) none-label)]
    [[(keyword (format "unicode-range-name-%d" index)) lkeys]
     [(keyword (format "unicode-range-ratio-%d" index)) 'numeric]]))

(defn- unicode-range-feature [text nth-best]
  (->> (lc/unicode-counts text :best-match? true)
       (sort (fn [a b]
               (compare (:count b) (:count a))))
       ((fn [data]
          (let [{cnt :count
                 name :name}
                (if (> (count data) nth-best)
                  (nth data nth-best)
                  {:name none-label
                   :count 0})
                [[name-key _]
                 [ratio-key _]]
                (unicode-range-feature-keys nth-best)]
            {name-key name
             ratio-key (ratio-0-if-empty cnt (count text))})))))

(defn- unicode-range-features [text bests]
  (->> (range bests)
       (map (fn [nth-best]
              (unicode-range-feature text nth-best)))
       (apply merge)))

(defn unicode-features
  "Create features based on the Unicode values of **text**:

  * **:unicode-variance** variance of Unicode (range) character counts
  * **:unicode-range-name-N** top Nth best (highest count) unicode name
  * **:unicode-range-ratio-N** top Nth best (highest count) unicode character ratio

  **nth-best-unicodes** are the number of range name/ratio features for Unicode
  ranges across characters in **text**."
  [text nth-best-unicodes]
  (let [text (s/replace text #"\s+" "")
        ucounts (lc/unicode-counts text :best-match? true)]
    (merge (unicode-range-features text nth-best-unicodes)
           {:unicode-variance (unicode-variance text ucounts)})))

(defn- unicode-range-feature-metas
  "See [[unicode-features]]."
  [nth-best-unicodes]
  (->> (range nth-best-unicodes)
       (map unicode-range-feature-keys)
       (apply concat)))

(defn unicode-feature-metas
  "See [[unicode-features]]."
  [nth-best-unicodes]
  (concat (unicode-range-feature-metas nth-best-unicodes)
          [[:unicode-variance 'numeric]]))



;; capitalized
(defn capital-features
  "Return features based on counts of capitalization of **tokens**.  Features
returned include (all integers):

  * **:caps-first-char-count** number of first character being capital (i.e. `Yes`, `YEs`, `YES`)
  * **:caps-first-char-ratio** number of first character being
  capital (i.e. `Yes`, `YEs`, `YES`) as a ratio to all characters across all
  tokens
  * **:caps-capitalized-count** number of capitalied tokens (i.e. `Yes`)
  * **:caps-capitalized-ratio** number of capitalied tokens (i.e. `Yes`) as a
  ratio to all other characters across all tokens
  * **:caps-all-count** number of all caps tokens (i.e. `YES`)
  * **:caps-all-ratio** number of all caps tokens (i.e. `YES`) as a ratio to
  all other characters across all tokens
  * **:cap-utterance** `true` if there exist any capitals in any tokens or
  `false` otherwise"
  [tokens]
  (log/tracef "capital features for <%s>" (pr-str tokens))
  (let [toks (map :text tokens)
        [cap capitalized caps]
        (StringUtils/countCapitals (into-array String toks))
        tlen (count toks)]
    {:caps-first-char-count cap
     :caps-first-char-ratio (ratio-0-if-empty cap tlen)
     :caps-capitalized-count capitalized
     :caps-capitalized-ratio (ratio-0-if-empty capitalized tlen)
     :caps-all-count caps
     :caps-all-ratio (ratio-0-if-empty caps tlen)
     :cap-utterance (->> toks first first
                         (#(and % (Character/isUpperCase %)))
                         (#(if % true false)))}))

(defn capital-feature-metas
  "See [[capital-features]]."
  []
  [[:caps-first-char-count 'numeric]
   [:caps-first-char-ratio 'numeric]
   [:caps-capitalized-count 'numeric]
   [:caps-capitalized-ratio 'numeric]
   [:caps-all-count 'numeric]
   [:caps-all-ratio 'numeric]
   [:cap-utterance 'boolean]])
