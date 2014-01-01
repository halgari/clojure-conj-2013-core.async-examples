# clojure-conj-talk

Here is are the code examples used in the 2013 Clojure Conj talk on core.async. The core.clj
file in this project contains the examples, and expects that users will eval the forms one 
at a time, top to bottom.

## Usage

In the talk, I used a binding (CTRL-`) and this emacs code to run each form one at a time in a repl. 


	
    (defun nrepl-eval-expression-at-point-in-repl ()
      (interactive)
      (let ((form (nrepl-expression-at-point)))
        ;; Strip excess whitespace
        (while (string-match "\\`\s+\\|\n+\\'" form)
          (setq form (replace-match "" t t form)))
        (set-buffer (nrepl-find-or-create-repl-buffer))
        (goto-char (point-max))
        (insert form)
        (nrepl-return)))

If you are using [CIDER](https://github.com/clojure-emacs/cider) below is the equivalent.



    (defun cider-eval-expression-at-point-in-repl ()
      (interactive)
      (let ((form (cider-sexp-at-point)))
        ;; Strip excess whitespace
        (while (string-match "\\`\s+\\|\n+\\'" form)
          (setq form (replace-match "" t t form)))
        (set-buffer (cider-find-or-create-repl-buffer))
        (goto-char (point-max))
        (insert form)
        (cider-repl-return)))





## License

Copyright © 2013 Timothy Baldridge

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
