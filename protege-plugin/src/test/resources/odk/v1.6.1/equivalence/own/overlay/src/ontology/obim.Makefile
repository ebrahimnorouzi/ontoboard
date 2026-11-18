## Customize Makefile settings for obim
##
## If you need to customize your Makefile, make
## changes here rather than in the main Makefile

## Three rules of the project's own, in nfdicore's shape (src/ontology/nfdicore.Makefile at
## 549a451): a module cut by MIREOT between ODK's seed's updates and nfdicore's, then sed over
## it; a module cut by BOT; and the base, built the project's way.

$(IMPORTDIR)/iao_import.owl: $(MIRRORDIR)/iao.owl $(IMPORTDIR)/iao_terms.txt
	if [ $(IMP) = true ]; then $(ROBOT) query -i $< --update ../sparql/preprocess-module.ru \
		extract --method MIREOT --upper-terms $(IMPORTDIR)/iao_terms_upper.txt --lower-terms $(IMPORTDIR)/iao_terms.txt --copy-ontology-annotations false --force true --individuals exclude \
		query --update ../sparql/inject-subset-declaration.ru --update ../sparql/inject-synonymtype-declaration.ru --update ../sparql/postprocess-module.ru \
		remove $(foreach p, $(ANNOTATION_PROPERTIES), --term $(p)) \
		        --term-file $(IMPORTDIR)/iao_terms.txt \
		        --select complement --select annotation-properties \
		$(ANNOTATE_CONVERT_FILE); sed -i "s/obofoundry/obolibrary/g" $(IMPORTDIR)/iao_import.owl ; fi

$(IMPORTDIR)/ro_import.owl: $(MIRRORDIR)/ro.owl $(IMPORTDIR)/ro_terms.txt
	if [ $(IMP) = true ]; then $(ROBOT) query -i $< --update ../sparql/preprocess-module.ru \
		extract -T $(IMPORTDIR)/ro_terms.txt --copy-ontology-annotations false --force true --individuals exclude --method BOT \
		query --update ../sparql/inject-subset-declaration.ru --update ../sparql/inject-synonymtype-declaration.ru --update ../sparql/postprocess-module.ru \
		remove $(foreach p, $(ANNOTATION_PROPERTIES), --term $(p)) \
		        --term-file $(IMPORTDIR)/ro_terms.txt \
		        --select complement --select annotation-properties \
		$(ANNOTATE_CONVERT_FILE); fi

$(ONT)-base.owl: $(EDIT_PREPROCESSED) $(OTHER_SRC) $(IMPORT_FILES)
	$(ROBOT_RELEASE_IMPORT_MODE) \
	reason --reasoner ELK --equivalent-classes-allowed asserted-only --exclude-tautologies structural --annotate-inferred-axioms False \
	relax \
	reduce -r ELK \
	remove --base-iri $(URIBASE)/OBIM_ --axioms external --preserve-structure false --trim false \
	$(SHARED_ROBOT_COMMANDS) \
	annotate --link-annotation http://purl.org/dc/elements/1.1/type http://purl.obolibrary.org/obo/IAO_8000001 \
		--ontology-iri $(ONTBASE)/$@ $(ANNOTATE_ONTOLOGY_VERSION) \
		--output $@.tmp.owl && mv $@.tmp.owl $@
