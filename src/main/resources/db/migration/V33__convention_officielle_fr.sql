-- ============================================================================
--  V33 — Convention de compte-titres : texte OFFICIEL Afriland First Bank (FR).
--
--  Remplace le gabarit V23 ('2026-01') par le texte reel fourni par la banque,
--  publie en version '2026-07'. La bascule respecte l'index partiel
--  uq_convention_current (une seule version courante par langue) : on retire
--  d'abord le drapeau de l'ancienne version FR, puis on insere la nouvelle.
--
--  contenu_html est dollar-quote ($conv$...$conv$) : aucun echappement des
--  apostrophes (omnipresentes dans le texte juridique) n'est necessaire.
--  Les champs a remplir (parties, lieu, date) restent des espaces reserves :
--  la convention est un document type presente au prospect avant acceptation.
-- ============================================================================

UPDATE convention_versions SET is_current = FALSE WHERE langue = 'FR' AND is_current;

INSERT INTO convention_versions (version, langue, titre, contenu_html, is_current)
VALUES (
    '2026-07', 'FR',
    'Convention de compte-titres — Afriland First Bank',
$conv$
<h1>Convention de compte-titres</h1>

<h2>Entre les Soussignés</h2>
<p><strong>Afriland First Bank</strong>, en abrégé « First Bank », Société Anonyme au
capital de FCFA cinquante milliards (50.000.000.000), dont le siège social est à
Yaoundé, Boîte Postale 11834, immatriculée au Registre de Commerce et du Crédit
Mobilier près le Greffe du Tribunal de Première Instance de Yaoundé sous le numéro
87 R 041, Établissement de crédit agréé, Intermédiaire du Marché Financier de
l'Afrique Centrale agréé par la Commission de Surveillance du Marché Financier de
l'Afrique Centrale sous le numéro COSUMAF-I.MFAC-01/2015, Spécialiste en Valeurs du
Trésor agréé dans les six États de la CEMAC (Cameroun, Gabon, Congo, Tchad,
République Centrafricaine, Guinée Équatoriale), ci-après dénommée le « Teneur de
compte » et représentée par :</p>
<ul>
  <li><strong>Représentant 1</strong> — Nom et Prénom : …………………… ; Fonction : …………………… ; dûment habilité ;</li>
  <li><strong>Représentant 2</strong> — Nom et Prénom : …………………… ; Fonction : …………………… ; dûment habilité ;</li>
</ul>
<p>Et Madame/Monsieur …………………………………………… ; Activité : …………………… ;
Localisation : …………………… ; N° Identité : …………………… ; Adresse complète :
…………………… ; ci-après dénommé(e) le « Client ».</p>
<p>Il a été convenu ce qui suit :</p>

<h2>Préambule</h2>
<p>La présente convention de compte-titres (ci-après « la Convention ») a pour objet de
régir les relations entre Afriland First Bank (ci-après « le Teneur de compte ») et
le(s) titulaire(s) désigné(s) (ci-après « le Client ») relatives à l'ouverture et au
fonctionnement d'un compte-titres. La Convention est notamment régie par :</p>
<ul>
  <li>Le règlement 01/14/CEMAC-UMAC-CM du 25 avril 2014 portant institution d'un régime d'inscription en compte des valeurs mobilières et autres instruments financiers dans la CEMAC ;</li>
  <li>Le règlement n° 03/19/CEMAC/UMAC/CM relatif aux valeurs du Trésor émises par les États membres de la CEMAC ;</li>
  <li>Le règlement n° 04/CEMAC/UMAC/CM relatif au marché des Titres de Créances Négociables de la CEMAC ;</li>
  <li>Le Règlement n° 01/22/CEMAC/UMAC/CM/COSUMAF portant organisation et fonctionnement du Marché Financier de l'Afrique Centrale ;</li>
  <li>Le Règlement Général de la Commission de Surveillance du Marché Financier de l'Afrique Centrale (COSUMAF) du 23 mai 2023 ;</li>
  <li>L'instruction n° 003/CRCT/2010 relative à la comptabilité-titres des teneurs de comptes ;</li>
  <li>L'instruction n° 02-10 du 28 avril 2010 de la COSUMAF ;</li>
  <li>L'instruction COSUMAF n° 03-15 du 10 décembre 2015 portant cahier de charges des teneurs de comptes conservateurs ;</li>
  <li>Le décret n° 2014/3763/PM du 13 novembre 2014 fixant les conditions d'application de la loi n° 2014/007 du 23 avril 2014 fixant les modalités de la dématérialisation des valeurs mobilières au Cameroun ;</li>
  <li>Le décret n° 2007/0457/PM du 4 avril 2007 modifiant et complétant certaines dispositions du décret n° 94/611/PM du 30 décembre 1994 portant réglementation de l'émission et de la gestion des Effets Publics Négociables.</li>
</ul>
<p>Les instruments financiers visés dans la Convention sont ceux visés par les textes sus-cités.</p>

<h2>Article 1. Conditions d'ouverture et de fonctionnement du compte-titres</h2>

<h3>1.1 Définition</h3>
<p>Le compte-titres est un compte qui accueille les dépôts de titres ou de valeurs
mobilières sous leur forme immatérielle. Il obéit à une organisation et une
comptabilité spécifiques et est indispensable à tout investisseur souhaitant faire
des transactions sur des valeurs mobilières ou sur le marché financier. Il enregistre
les transactions sur titres. Le compte-titres est obligatoirement rattaché à un compte
bancaire classique ouvert au « Client » par « Afriland First Bank ».</p>
<p>La tenue de comptes consiste, d'une part, à enregistrer dans les livres du Teneur de
compte les écritures comptabilisant les différents mouvements et opérations sur valeurs
mobilières et, d'autre part, à conserver et administrer lesdits titres pour le compte
du Client.</p>

<h3>1.2 Conditions générales d'ouverture</h3>
<p>Toute personne physique ou morale capable ou dûment représentée peut être titulaire
d'un compte-titres. Néanmoins, la Banque demeure libre de refuser l'ouverture d'un
compte-titres sans avoir à motiver sa décision.</p>
<p>Le Teneur de compte ouvre un compte-titres après avoir opéré les vérifications
nécessaires conformément aux dispositions légales et réglementaires en vigueur. La
vérification de l'identité et du domicile du Client se fait sur présentation d'une
pièce d'identité officielle portant signature (avec recueil d'une photocopie) en cours
de validité et d'un justificatif de domicile datant de moins de trois mois (facture
d'électricité, d'eau ou de téléphone fixe, ou géolocalisation Google Map).</p>
<p>Le cas échéant, devront être fournis préalablement à l'ouverture du compte-titres les
documents officiels justifiant des pouvoirs du (ou des) représentant(s) légal(aux) ou
judiciaire(s) du Client.</p>
<p>L'ouverture du compte-titres s'accompagne du dépôt d'un spécimen de signature du (ou
des) titulaire(s) du compte et des personnes habilitées à le faire fonctionner, chez
le Teneur de compte.</p>
<p>Les dispositions ci-dessus sont applicables à tous les co-titulaires d'un compte
collectif. Tout mandataire éventuellement désigné pour faire fonctionner le compte doit
également justifier de son identité et de son domicile dans les mêmes conditions
ci-dessus précisées. Le Teneur de compte conserve les références ou la copie des divers
documents présentés.</p>
<p>Le compte-titres est obligatoirement associé à un compte espèces (compte courant) sur
lequel sont enregistrés les crédits et débits correspondant aux opérations effectuées
sur le compte-titres. Le Client qui ouvre un compte-titres reconnaît expressément avoir
pris connaissance et accepté les conditions du Teneur de compte. Le compte espèces doit
être approvisionné avant la passation d'un ordre d'achat, de façon à permettre le
règlement (par prélèvement) de l'ordre et des frais associés le cas échéant.</p>
<p>Le compte-titres peut être un compte individuel, joint, indivis ou un compte en
usufruit et nue-propriété, tel que défini à l'article 1.3 infra.</p>
<p>Le Teneur de compte doit par ailleurs être informé de tout changement dans la
situation du Client (changement d'état civil, régime matrimonial, capacité juridique…)
et de tout élément susceptible d'affecter le fonctionnement du compte-titres (adresse,
numéros de téléphone…). À défaut d'une telle information, le Teneur de compte considérera
comme exactes les informations dont il dispose. Les éléments d'identification du
compte-titres et du compte espèces associé figurent sur le formulaire d'ouverture du
compte-titres.</p>

<h3>1.3 Dispositions spécifiques à certaines natures de compte-titres</h3>

<h4>1.3.1 Compte-titres joint</h4>
<p>Le compte-titres joint permet à chaque co-titulaire de faire, séparément, toutes
opérations sur ce compte-titres. Les co-titulaires sont tenus solidairement vis-à-vis
du Teneur de compte de l'exécution de tous engagements portant la signature de l'un
d'entre eux.</p>
<p>Sans accord du (ou des) autre(s) co-titulaire(s), il peut être mis fin à la situation
de compte-titres joint par désolidarisation d'un des co-titulaires, par lettre
recommandée avec accusé de réception adressée au Teneur de compte. Cette désolidarisation
a pour effet la transformation, après réception de la lettre recommandée avec accusé de
réception, du compte-titres joint en compte-titres indivis (voir ci-après). Chaque
co-titulaire reste néanmoins tenu solidairement pour les opérations effectuées avant
cette désolidarisation.</p>
<p>Un compte-titres joint ne peut être ouvert avec une personne morale, un mineur non
émancipé ou un majeur protégé.</p>

<h4>1.3.2 Compte-titres indivis</h4>
<p>Le compte-titres indivis est un compte qui est ouvert au nom de plusieurs
co-indivisaires et qui requiert, tant pour son ouverture que pour son fonctionnement,
la signature conjointe de tous les co-indivisaires, sauf procuration donnée à l'un
d'eux ou à un tiers, ou mandat réciproque. Les co-indivisaires sont tenus solidairement
de leurs engagements vis-à-vis de la Banque.</p>

<h4>1.3.3 Compte-titres en usufruit et nue-propriété</h4>
<p>Le compte-titres en nue-propriété est un compte en démembrement de propriété où le
capital appartient au nu-propriétaire et les revenus des titres sont versés sur le
compte espèces ouvert au nom de l'usufruitier.</p>
<p>Les ordres et actes de disposition (notamment les ordres d'achat et de vente ou les
ordres de transfert ou de virement) doivent être signés par l'usufruitier et le
nu-propriétaire, sauf s'il existe une procuration donnée à l'un d'eux ou un mandat
réciproque. Les actes d'administration courante pourront être faits à la seule
initiative de l'usufruitier.</p>
<p>Seul le nu-propriétaire, en sa qualité d'actionnaire, peut exercer les droits attachés
à sa qualité d'actionnaire (notamment exercer l'option du paiement de dividendes en
actions proposée par l'assemblée des actionnaires). L'usufruitier est seul responsable
d'avertir le nu-propriétaire pour lui permettre d'exercer ses droits. L'usufruitier
bénéficie du paiement du dividende et des fruits des titres.</p>
<p>En cas de cession, remboursement ou amortissement des titres, le capital est versé sur
le compte espèces du nu-propriétaire, qui a l'entière responsabilité du réemploi des
sommes provenant des titres cédés, remboursés ou amortis.</p>

<h4>1.3.4 Compte-titres de mineurs</h4>
<p>L'ouverture d'un compte-titres au nom d'un mineur ainsi que les opérations sur le
compte-titres sont réalisées par le(s) représentant(s) légal(aux), seul(s)
responsable(s) de la régularité du fonctionnement du compte.</p>

<h4>1.3.5 Compte-titres de majeurs protégés</h4>
<p>L'ouverture et le fonctionnement des comptes-titres ouverts aux majeurs protégés sont
soumis au régime de protection ordonné par l'autorité judiciaire (sauvegarde de justice,
administration légale sous contrôle judiciaire, curatelle, tutelle…). L'ouverture du
compte-titres ne peut se faire que sur présentation et dans les conditions définies dans
la décision de justice. Le compte fonctionnera en conséquence selon le régime de
protection ordonné et selon les modalités fixées par l'autorité judiciaire.</p>
<p>En cas de survenance d'une mesure de protection en cours de vie du compte-titres, il
appartient au représentant du Client, devenu majeur protégé, d'informer le Teneur de
compte de la mesure de mise en protection sur présentation de la pièce justificative
(copie de la décision de justice).</p>

<h3>1.4 Titres nominatifs administrés</h3>
<p>Les titres nominatifs administrés sont des titres financiers individualisés inscrits
dans les registres de la société émettrice sous le nom de leur détenteur, qui en confie
par ailleurs l'administration à son intermédiaire financier habituel.</p>
<p>À cet effet, le (ou les) titulaire(s) du compte donne(nt) mandat par la présente
Convention à la Banque, qui l'accepte, d'administrer ses titres financiers nominatifs
dont les inscriptions figurent en compte chez les émetteurs et seront reproduites à
son (ou leurs) compte(s)-titres ouvert(s) dans les livres de la Banque. En vertu de ce
mandat, le Teneur de compte effectuera tous les actes d'administration pour le compte
du (ou des) Client(s), et notamment l'encaissement des produits (revenus, dividendes…).</p>
<p>En revanche, les actes de disposition, notamment l'exercice des droits aux
augmentations de capital et les règlements titres ou espèces, sont effectués sur
instruction expresse et préalable du (ou des) Client(s). Le (ou les) Client(s)
pourra(ont) se prévaloir de son (leur) acceptation tacite pour certaines opérations
conformément aux usages en vigueur.</p>
<p>Le Teneur de compte avise le (ou les) Client(s) des opérations qui affectent le
compte-titres. Les relevés de portefeuille et les comptes rendus d'opérations sont
envoyés selon les modalités prévues à l'article 10 « Informations » de la présente
Convention.</p>
<p>Les droits pécuniaires (dividendes, attribution d'actions gratuites…) attachés aux
titres nominatifs administrés peuvent être exercés indifféremment par les co-titulaires
dans le cas d'un compte-titres joint. Les droits extra-pécuniaires (participation et
vote aux assemblées générales des actionnaires…) attachés à ces titres financiers sont
exercés par le co-titulaire premier nommé dans l'intitulé du compte-titres.</p>
<p>S'agissant de titres financiers faisant l'objet d'une inscription directe chez
l'émetteur, le Client reconnaît avoir été informé des risques liés notamment à la
mauvaise exécution par l'émetteur des instructions sur ces titres, à de potentielles
difficultés de reconnaissance des droits du Client — dont le Teneur de compte ne pourra
pas être tenu responsable — et aux erreurs de valorisation concernant ces titres.</p>
<p>Le mandat d'administration peut être dénoncé à tout moment et sans aucun préavis par
l'une ou l'autre partie, par lettre recommandée avec accusé de réception, et prendra
effet à réception de la lettre. Cette dénonciation entraîne la clôture immédiate du
compte-titres.</p>

<h3>1.5 La procuration</h3>
<p>Le Client peut, par écrit et pour une durée indéterminée, donner procuration à une ou
plusieurs personne(s) physique(s) capable(s) et non interdite(s) bancaire(s) ou
judiciaire(s) (ci-après dénommé le « Mandataire ») afin d'effectuer, en son nom, pour
son compte et sous son entière responsabilité, toutes opérations sur son compte-titres,
à l'exception de la clôture. Le Client s'engage à informer personnellement son (ou ses)
mandataire(s) des termes de la Convention.</p>
<p>La procuration sera donnée par acte séparé précisant la nature des opérations pouvant
être effectuées par le mandataire. Dans le cas d'un compte-titres joint, la signature
de tous les co-titulaires sera exigée. Dans ce dernier cas, la (ou les) personne(s)
choisie(s) doit(vent) également être autorisée(s) à faire fonctionner le compte espèces
associé.</p>
<p>Le Mandataire désigné doit justifier de son identité et de son domicile, et présenter
à ce titre : un document officiel d'identité probant en cours de validité comportant sa
photographie ; un justificatif de domicile datant de moins de 3 mois ; déposer un
spécimen de sa signature et être approuvé par le Teneur de compte. En cas de refus de
l'approbation du Mandataire, le Teneur de compte en informera le Client par écrit et par
tout moyen dans les meilleurs délais.</p>
<p>Les opérations effectuées par le Mandataire, dans le cadre des pouvoirs que le Client
lui a confiés, engagent l'entière responsabilité du Client. Le Mandataire d'un compte
collectif doit être désigné d'un commun accord et par écrit par tous les co-titulaires
du compte-titres.</p>
<p>Cette procuration prend fin, que le compte-titres soit individuel ou collectif, dans
les cas suivants :</p>
<ul>
  <li>révocation de la procuration par le Client ;</li>
  <li>renonciation à la procuration par le Mandataire ;</li>
  <li>décès ou incapacité du Client ou du Mandataire ;</li>
  <li>changement d'un des titulaires d'un compte joint ou indivis, ou transformation en compte individuel ;</li>
  <li>clôture du compte espèces ou du compte-titres.</li>
</ul>
<p>Dans tous les cas, le Client ou, le cas échéant, le Mandataire doit notifier au Teneur
de compte la survenance de l'une des situations listées ci-dessus, par pli recommandé
avec accusé de réception. La révocation ou la renonciation prendra effet à la date de
réception du courrier par le bureau de poste gestionnaire ou le Centre financier, sous
réserve des opérations en cours. Le Client qui révoque par écrit une procuration doit en
informer simultanément son Mandataire et, le cas échéant, s'engage à en justifier auprès
de la Banque.</p>

<h2>Article 2. Catégorisation et classification des clients</h2>

<h3>2.1 Catégorisation et changement de catégorie des clients</h3>
<p>Le Client est informé par le Teneur de compte, sur le formulaire d'ouverture de
compte-titres, de sa catégorisation en qualité d'« Investisseur qualifié » ou
d'« Investisseur non qualifié ».</p>
<h4>2.1.1 Les catégories d'investisseurs</h4>
<p>L'article 90 du Règlement n° 01/22/CEMAC/UMAC/CM/COSUMAF définit l'investisseur
qualifié comme une personne ou une entité disposant des compétences et des moyens
nécessaires pour appréhender les risques inhérents aux opérations sur instruments
financiers. Tout investisseur ne répondant pas à cette définition est dit « investisseur
non qualifié ».</p>
<h4>2.1.2 Changement de catégorie</h4>
<p>Le Client s'engage à informer la Banque de tout changement susceptible de modifier sa
catégorisation. Le Client peut demander à changer de catégorie pour se placer soit sous
un régime plus protecteur, soit pour renoncer à une partie des protections dont il
bénéficie. La demande de changement de catégorie doit être adressée par courrier au
Teneur de compte, qui se réserve le droit de la refuser.</p>

<h3>2.2 Classification des clients</h3>
<p>En considération des informations que lui a communiquées le Client, notamment sur sa
situation financière, son expérience et ses connaissances en matière financière, le
Teneur de compte détermine la classification dont le Client relève. En l'absence de ces
informations, le Teneur de compte doit s'abstenir de fournir le service de conseil en
investissement et catégorise d'office le Client en « Investisseur non qualifié ».</p>
<p>Dans le cadre du service de réception et transmission d'ordres pour le compte de tiers,
le Teneur de compte informe le Client qu'il n'est pas tenu d'évaluer le caractère
approprié du service ou de l'instrument financier. L'ordre transmis par le Client, de sa
propre initiative, relève de l'exécution simple.</p>

<h2>Article 3. Classification et forme des instruments financiers inscriptibles en compte</h2>

<h3>3.1 Classification des instruments financiers</h3>
<p><strong>3.1.1</strong> Les valeurs mobilières et autres instruments financiers ou
titres assimilés émis dans le cadre d'un appel public à l'épargne au sens de l'article
20 du Règlement Général de la COSUMAF, principalement :</p>
<ul>
  <li>les titres de capital et de créances émis par une société anonyme ;</li>
  <li>les bons du Trésor, obligations du Trésor et tout autre instrument financier émis par la Banque des États de l'Afrique Centrale, un État de la CEMAC ou un démembrement de cet État ;</li>
  <li>les parts ou actions d'OPCVM ;</li>
  <li>tout autre instrument émis dans le cadre d'un appel public à l'épargne.</li>
</ul>
<p><strong>3.1.2 Les valeurs du Trésor :</strong> les BTA (Bons du Trésor Assimilables)
et les OTA (Obligations du Trésor Assimilables).</p>
<p><strong>3.1.3</strong> Les valeurs mobilières et autres instruments financiers ou
titres assimilés émis au Cameroun. En application de la loi n° 2014/007 du 23 avril 2014
fixant les modalités de la dématérialisation des valeurs mobilières au Cameroun, les
valeurs mobilières cotées ou non cotées, émises par des entités publiques ou privées
ayant cours en République du Cameroun, doivent faire l'objet d'une inscription en compte.
Une valeur mobilière, au sens de l'article 2 de la loi susmentionnée, est un titre
représentatif d'une participation (action) ou d'une créance (obligation) émis par des
personnes morales publiques ou privées, transmissible par inscription en compte, qui
confère des droits identiques par catégories et qui donne accès, directement ou
indirectement, à une quotité du capital de la personne morale émettrice, à un droit de
créance général sur son patrimoine ou aux droits y rattachés. Sont assimilés à des
valeurs mobilières : les titres d'emprunt public négociables sur les marchés réglementés
émis par l'État, les collectivités territoriales décentralisées ou tout autre
établissement public ; les parts ou actions d'OPCVM ; les autres instruments financiers
émis par l'État, les collectivités territoriales décentralisées ou tout autre
établissement public sur des marchés organisés.</p>
<p><strong>3.1.4 Les titres de créances négociables (TCN)</strong> concernés sont ceux
définis dans le règlement n° 04/CEMAC/UMAC/CM relatif au marché des titres de créances
négociables de la CEMAC. Il s'agit notamment des billets de trésorerie, des certificats
de dépôts et des bons à moyen terme négociables émis par les personnes habilitées, tel
que précisé à l'article 13 dudit règlement. Les TCN en compte se transmettent par
virement de compte à compte ; le transfert de la propriété des TCN résulte de leur
inscription au compte de l'acquéreur ; toute opération de débit d'un compte de titres
est subordonnée à une instruction signée du titulaire du compte ou de son représentant
dûment habilité à cet effet.</p>
<p><strong>3.1.5 Les autres titres :</strong> tous les autres titres dématérialisés émis
au Cameroun ou dans la CEMAC et régis par les textes en vigueur.</p>

<h3>3.2 La forme des instruments financiers</h3>
<p>Les titres financiers revêtent la forme soit de titres au porteur, soit de titres
nominatifs. Conformément à l'article 7 du règlement 01/14/CEMAC-UMAC-CM du 25 avril 2014,
lorsque les titres ou instruments financiers sont inscrits dans un compte-titres tenu par
un intermédiaire financier agréé, ils sont dits « au porteur ». Conformément à l'article
8 du même règlement, lorsqu'ils sont inscrits dans un compte-titres tenu par la personne
morale émettrice, ils sont dits « nominatifs ». Les titres nominatifs administrés par les
personnes morales émettrices sont dits « nominatifs purs ». Les titres nominatifs
administrés, en vertu d'un mandat, par un intermédiaire financier agréé, sont dits
« nominatifs administrés ».</p>

<h2>Article 4. Les marchés</h2>
<p>Le Teneur de compte réalise les transactions sur les titres financiers (marché des
actions et des obligations) admis aux négociations sur le Marché Financier de l'Afrique
Centrale et le Marché des titres publics assimilables émis par adjudication de la
CEMAC.</p>

<h2>Article 5. Les ordres</h2>
<p>Les ordres sont transmis en francs CFA.</p>

<h3>5.1 Couverture des ordres</h3>
<p>Un ordre d'achat n'est valable, c'est-à-dire accepté par le Teneur de compte, que si
le Client constitue préalablement auprès d'Afriland First Bank une provision espèces
suffisante. Cette provision est fonction des quantités demandées et du prix unitaire
coté des titres demandés. Le Client ne peut vendre que les titres qu'il détient
effectivement sous bonne date de valeur, et n'acheter des titres qu'à hauteur de sa
limite d'autorisation ou de la provision espèces qu'il aura préalablement constituée.</p>
<p>En cas d'absence de provision, le Teneur de compte ne peut être mis en cause pour non
routage de l'ordre vers la Bourse. Tout ordre régulièrement enregistré et exécuté par le
Teneur de compte ne pourra plus être contesté par le Client. Les prélèvements liés à des
opérations réalisées conformément aux ordres du Client sont effectués d'office sur le
compte espèces associé et ne peuvent faire l'objet d'une interdiction de payer de la part
du Client. Lors d'un achat, les titres financiers sont définitivement acquis au Client
dès lors qu'ils ont été payés.</p>

<h3>5.2 Mentions obligatoires sur les ordres du client</h3>
<p>Tout ordre d'achat ou de vente de titre doit comporter les informations suivantes,
sous peine de nullité :</p>
<ul>
  <li>la désignation du titre objet de la transaction ;</li>
  <li>le nombre de titres objet de la transaction ;</li>
  <li>le sens de l'opération ;</li>
  <li>le prix ou la fourchette de prix ;</li>
  <li>la durée de validité de l'ordre ;</li>
  <li>l'identité complète du donneur d'ordre ;</li>
  <li>la date de dépôt de l'ordre ;</li>
  <li>la signature du Client ;</li>
  <li>les numéros de comptes espèces et titres du Client ;</li>
  <li>la provenance des fonds (relevé du compte espèces tenu dans une banque, un établissement de micro-finance ou un établissement de paiement).</li>
</ul>

<h3>5.3 Les types d'ordre</h3>
<h4>5.3.1 L'ordre « à cours limité »</h4>
<p>Il s'agit d'un ordre par lequel le donneur d'ordre acheteur fixe le prix maximal qu'il
est disposé à payer, ou par lequel le donneur d'ordre vendeur fixe le prix minimal auquel
il accepte de céder ses titres. Ce type d'ordre présente l'intérêt de permettre la
maîtrise du prix d'exécution, mais le donneur d'ordre n'a pas la garantie d'avoir son
ordre exécuté dans son intégralité : il peut être exécuté partiellement, voire ne pas
être exécuté.</p>
<h4>5.3.2 L'ordre « au marché »</h4>
<p>Il n'est assorti d'aucune limite et peut faire l'objet d'une exécution partielle.
L'ordre au marché est exécuté au maximum de la quantité immédiatement disponible, son
solde restant en carnet. Si un ordre au marché ne trouve pas de contrepartie, il reste
en carnet jusqu'à son exécution ou son annulation, soit par le Client, soit du fait de
l'atteinte de sa limite de validité. En séance, l'ordre « au marché » est exécuté
totalement ou partiellement selon les possibilités offertes sur la feuille de marché.</p>

<h3>5.4 Le prix de l'ordre</h3>
<p>Le prix de l'ordre est exprimé à la pièce, en taux d'intérêt ou en pourcentage du
nominal.</p>

<h3>5.5 La taille de l'ordre</h3>
<p>Le volume d'un ordre s'exprime par une quantité de titres ou par un montant.</p>

<h3>5.6 Ordres sur OPCVM</h3>
<p>Tous les ordres de souscription ou de rachat d'OPCVM (SICAV, FCP) déposés auprès du
Teneur de compte sont négociés au comptant et exécutés conformément aux dispositions
prévues par le prospectus complet ou, à défaut, la notice d'information. Le prospectus
simplifié ou la notice d'information est remis préalablement à toute souscription.</p>

<h2>Article 6. Transmission des ordres</h2>

<h3>6.1 Modalités de transmission des ordres</h3>
<p>Le Client titulaire d'un compte-titres peut passer des ordres d'achat ou de vente
auprès des guichets du Teneur de compte. Pour cela, il doit remplir le « formulaire
d'ordre » mis à sa disposition par le Teneur de compte et le signer. Le Client peut opter
pour tout autre mode de transmission mis à sa disposition par le Teneur de compte
(Internet, téléphone via les services de banque en ligne, sous réserve
d'authentification).</p>
<p>Le Client assume la responsabilité de tout mode de transmission d'ordres convenu avec
le Teneur de compte et décharge ce dernier des conséquences pouvant résulter de
l'utilisation du (ou des) moyen(s) de communication choisi(s) par le Client pour
transmettre son ordre.</p>
<p>Les ordres par téléphone sont admis s'ils sont passés à partir d'une ligne privée dont
le numéro est communiqué au Teneur de compte lors de la signature de la présente
Convention. Pour cela, le Client devra toujours décliner ce numéro à chaque ordre passé
par téléphone. Les ordres passés par téléphone doivent être confirmés par un formulaire
d'ordre dûment rempli et signé par le Client.</p>
<p>Le Teneur de compte peut à tout moment ne plus accepter certains modes de passation
d'ordre, sous réserve d'en avoir préalablement informé le Client par tous moyens,
notamment lorsqu'un litige est survenu sur ces modes de transmission. Le Client est
expressément informé du fait que la transmission de l'ordre en vue de son exécution ne
préjuge pas de son exécution. Le Teneur de compte n'acceptera pas les ordres dont les
conditions d'exécution ne seraient pas conformes à la réglementation en vigueur ou
s'avéreraient incompatibles avec les conditions de marché.</p>
<p>Le Teneur de compte peut fixer des limites d'intervention selon la nature de
l'opération et en fonction de chaque type de titre financier et du marché concerné. Ces
limites sont opposables de plein droit au Client. Tout ordre transmis qui franchirait ces
limites pourrait ne pas être exécuté. Dans le cas où l'ordre n'aurait pas pu être
transmis, le Teneur de compte fait ses meilleurs efforts pour informer le Client. L'ordre
qui n'a pas pu être transmis est réputé expiré ; il appartient au Client d'émettre, le
cas échéant, un nouvel ordre.</p>
<p>L'ordre peut n'être exécuté que partiellement. À défaut d'instructions expresses du
Client, tout commencement d'exécution partielle engage le Client. Lorsqu'un ordre du
Client présentera un caractère inhabituel en fonction de sa nature, de ses modalités, du
marché ou de son montant, le Teneur de compte, préalablement à la transmission de l'ordre
pour exécution, avertira le Client de cette situation et lui fournira les informations
utiles à sa compréhension de l'opération envisagée et des risques qui y sont attachés.
Après avoir pris connaissance de ces informations, le Client pourra alors confirmer son
ordre.</p>
<p>Chaque ordre doit être dûment complété de l'ensemble des rubriques nécessaires à sa
transmission et à sa bonne exécution sur le marché, tel que précisé à l'article 5.2. Il
doit également être daté et signé par le Client (ou son représentant légal ou son
mandataire) avant sa transmission sur le marché. En l'absence de ces rubriques, le Teneur
de compte n'est pas tenu de transmettre l'ordre. Tout ordre exécuté ne peut être annulé.</p>
<p>Compte tenu des aléas pouvant intervenir lors de la transmission des ordres par
correspondance ou par télécopie, le Teneur de compte ne saurait être tenu responsable du
défaut d'exécution ou de l'exécution tardive des ordres transmis. L'ordre est adressé au
Teneur de compte sous la seule responsabilité du Client. L'attention du Client est
spécifiquement attirée sur la possibilité de délai, dont la durée est imprévisible, entre
le moment où il émet l'ordre et celui auquel la Banque reçoit ce même ordre. Dans tous les
cas, les éléments attestant de la passation de ces ordres par le Client et de leur
conformité avec les écritures du Teneur de compte font foi.</p>
<p>Le Teneur de compte se réserve la possibilité, dans l'intérêt du Client et lorsqu'il
n'a pas la qualité d'établissement placeur, de refuser la réception-transmission d'ordre
de certaines émissions d'emprunts obligataires sur le marché primaire.</p>
<p>Le Client a la faculté de demander l'annulation de son ordre après sa transmission.
Cette demande ne pourra toutefois être prise en compte que dans la mesure où l'ordre n'est
pas exécuté. Le Teneur de compte traitera cette demande d'annulation dans les meilleurs
délais. Les frais d'annulation seront à la charge du Client. Tout ordre exécuté malgré
une demande d'annulation tardive sera inscrit sur le compte-titres du Client.</p>

<h3>6.2 Durée de validité des ordres de bourse</h3>
<p>a) Validité « jour » : l'ordre de bourse n'est exécutable que pendant la journée de
négociation en cours et sera d'office retiré du marché s'il n'a pas été exécuté.</p>
<p>b) Validité « à date déterminée » : l'ordre de bourse reste présent sur le marché tant
qu'il n'a pas été exécuté et jusqu'à la date indiquée par le donneur d'ordre. Cette date
ne pourra pas dépasser 365 jours civils.</p>
<p>Si le donneur d'ordre omet d'indiquer une validité, la validité « jour » est appliquée
d'office. En cas de non-exécution d'un ordre au jour d'expiration de sa validité, un
nouvel ordre est nécessaire pour le renouveler, même si les conditions d'exécution sont
identiques. En cas de détachement d'un droit de souscription ou d'attribution, les ordres
non exécutés sont automatiquement retirés du marché et doivent par conséquent être
renouvelés par les donneurs d'ordre ; il en est de même, sauf exception, dans le cas des
suspensions de cotation. Lors du détachement d'un dividende, les ordres non exécutés
restent présents sur le marché mais leur limite est abaissée du montant du dividende
net.</p>

<h2>Article 7. Conservation des titres</h2>
<p>En tant que teneur de compte conservateur, la Banque assure la garde de l'ensemble des
titres financiers inscrits en compte et accomplit notamment, à cet effet, l'encaissement
des dividendes ou des coupons, l'exercice des droits et l'amortissement ou le
remboursement des titres financiers. Le Teneur de compte conservera et restituera les
titres financiers déposés sur simple demande du Client, sous réserve des indisponibilités
provenant des mises en garantie (nantissement, etc.) ou de droits que pourraient faire
valoir des tiers. Les titres financiers inscrits en compte ne peuvent faire l'objet d'une
utilisation par le Teneur de compte, sauf accord préalable et exprès du Client.</p>
<p>Pour les titres financiers qu'il a en conservation, le Teneur de compte s'oblige à
respecter les règles de place relatives à la conservation des titres, notamment celles
définies dans l'instruction COSUMAF n° 03-15 du 10 décembre 2015 portant cahier de
charges des teneurs de compte conservateurs. Pour les titres financiers étrangers,
négociés sur des marchés étrangers, le Teneur de compte est autorisé expressément par le
Client à fournir aux conservateurs étrangers tous les renseignements utiles. Le Teneur de
compte se réserve toutefois le droit de refuser l'inscription en compte et la négociation
des titres financiers émis et conservés à l'étranger.</p>

<h2>Article 8. Responsabilités du teneur de compte</h2>
<p>Le Teneur de compte agit conformément aux usages, règles de l'art et pratiques de la
profession bancaire, dans le respect des lois et règlements en vigueur. Le Teneur de
compte n'est soumis qu'à une obligation de moyen et sa responsabilité ne pourra être
engagée que sur la base d'une faute grave prouvée par le Client. Seuls les dommages
directs subis par le Client pourront alors donner lieu à une éventuelle indemnisation. Le
Teneur de compte n'est pas responsable des pertes de chance pouvant résulter des choix ou
omissions du Client.</p>
<p>Dans le cadre de la présente Convention, la responsabilité du Teneur de compte ne
saurait être engagée dans les cas suivants, sans que cette liste soit exhaustive : tout
événement relevant de la force majeure ; tout incident de fonctionnement sur les lieux
d'exécution, conflit social, grève ou suppression de cotation ; en cas de défaillance de
l'entreprise de marché sur laquelle le Teneur de compte intervient à la demande du
Client.</p>

<h2>Article 9. Risques engendrés par les opérations sur titres financiers</h2>
<p>Le Client reconnaît avoir pris connaissance de la réglementation et du fonctionnement
des marchés sur lesquels il effectue des transactions. Il reconnaît également être
conscient des risques inhérents à ces transactions, de leur caractère spéculatif et des
risques de perte du capital investi.</p>
<p>Le Client reconnaît également le fait qu'il doit se tenir informé personnellement de
tout événement affectant la vie de toute société émettrice de titres en dépôt et
susceptible d'influer sur la valeur de ces titres — comme, par exemple, le redressement
ou la liquidation judiciaire de la société émettrice — le Teneur de compte n'assumant
aucune obligation d'information à cet égard.</p>

<h2>Article 10. Informations</h2>
<ul>
  <li><strong>Périodicité des relevés de compte-titres :</strong> le Teneur de compte informera le Client ou son mandataire de la situation de son portefeuille par l'envoi d'un relevé de compte mensuel récapitulant l'ensemble des opérations et mouvements intervenus sur le compte au cours du mois.</li>
  <li><strong>Informations relatives à l'exécution des ordres :</strong> à chaque transaction valablement exécutée, le Teneur de compte envoie au Client ou à son mandataire, au plus tard huit jours ouvrés suivant le dénouement effectif de la séance de bourse, un « avis d'opéré » l'informant de l'exécution de l'ordre et des termes de cette exécution.</li>
  <li>Les informations relatives aux allocations de titres à la suite d'une opération de souscription de titres sur le marché primaire sont notifiées au Client ou à son mandataire par un document appelé « avis de souscription et d'inscription en compte », confirmant l'acceptation de la souscription et l'attribution définitive des titres par l'émetteur.</li>
</ul>
<p>Dans le cas où le compte-titres est ouvert au nom de plusieurs titulaires (compte-titres
indivis, joint entre tiers), les avis d'opérations et les relevés de portefeuille sont
adressés au premier co-titulaire nommé dans l'intitulé du compte-titres ou à la personne
désignée pour recevoir ces informations. Dans le cas où le Client ne recevrait pas ces
documents dans les délais habituels, il lui appartient d'en informer le Teneur de compte
dans les meilleurs délais.</p>

<h2>Article 11. Autres dispositions</h2>
<p>Le Teneur de compte sollicite du Client toutes instructions utiles à l'accomplissement
des formalités facultatives afférentes à ses droits pécuniaires. Faute d'instruction
précise dans les délais requis, le Teneur de compte est habilité à prendre toutes mesures
dans l'intérêt du Client, notamment la vente en bourse desdits droits, sans que celles-ci
puissent engager sa responsabilité. Dans tous les cas, le Teneur de compte agira dans le
cadre d'une obligation de moyen, dans l'intérêt premier de ses clients.</p>
<h3>11.1 Transfert du compte-titres</h3>
<p>Le Client peut demander le transfert de son compte-titres vers un autre teneur de
compte. Ce transfert est effectué dès lors que le règlement de toute somme due, en vertu
des présentes, au Teneur de compte a été normalement acquitté. Les frais de transfert
sont à la charge du Client. Il est expressément convenu que, durant la période de
transfert, le Client ne pourra pas confier d'opération d'achat (souscription) ou de vente
(rachat) de titres.</p>
<h3>11.2 Réclamations / Contestations</h3>
<p>Les réclamations concernant un ordre de bourse ou d'OPCVM doivent être formulées par
écrit au Teneur de compte, avec accusé de réception, dans les deux jours qui suivent la
réception de l'avis d'opération. Passé ce délai, l'opération est présumée acceptée par le
Client. Toute autre réclamation pourra seulement être acceptée par écrit dans un délai
d'un mois après réception du relevé de portefeuille.</p>

<h2>Article 12. Tarification</h2>
<p>Le Client est informé que la tarification des services liés à la gestion des
comptes-titres est régie par les textes en vigueur. La gestion du compte-titres est
soumise à tarification. Des droits de garde sont prélevés sur le compte espèces du Client
en fin de semestre. La passation d'ordres donne lieu à la perception d'une commission par
le Teneur de compte. Le Client déclare avoir pris connaissance des conditions générales de
tarification du Teneur de compte. Toute modification de tarif sera portée préalablement à
la connaissance du Client.</p>

<h2>Article 13. Fiscalité</h2>
<p>La fiscalité applicable sur les produits du portefeuille-titres du Client est celle de
son pays de résidence.</p>

<h2>Article 14. Obligations du teneur de compte-titres</h2>
<h3>14.1 Secret professionnel</h3>
<p>Le Teneur de compte est tenu au secret professionnel. Toutefois, ce secret peut être
levé dans les cas prévus par la loi, notamment à l'égard des autorités de contrôle, de
l'administration fiscale et des autorités pénales. De même, en matière de lutte contre le
blanchiment des capitaux et le financement du terrorisme, le Teneur de compte est tenu de
transmettre aux entreprises du groupe auquel il appartient les informations couvertes par
le secret professionnel.</p>
<p>De convention expresse, le Client autorise le Teneur de compte à communiquer toute
information utile le concernant à toute personne physique ou morale contribuant à la
réalisation des prestations prévues par la Convention ou qui pourrait y être
ultérieurement rattachée, notamment aux prestataires de services pour l'exécution des
travaux sous-traités et/ou aux sociétés du groupe pour leur utilisation aux fins d'étude
et de gestion des dossiers, de prospection commerciale et/ou d'autres études
statistiques.</p>
<h3>14.2 Lutte contre le blanchiment des capitaux et le financement du terrorisme</h3>
<p>Le Teneur de compte est soumis à une obligation de vigilance et d'information, en raison
des dispositions législatives et réglementaires relatives à la lutte contre le blanchiment
de capitaux et le financement du terrorisme.</p>
<h3>14.3 Surveillance des opérations sur titres financiers</h3>
<p>Le Teneur de compte est soumis à une obligation de vigilance et d'information, en
application des dispositions législatives et réglementaires relatives au blanchiment des
capitaux et au financement du terrorisme en Afrique Centrale. Le Teneur de compte doit
notamment déclarer aux autorités compétentes les opérations mentionnées à l'article 6 de
l'instruction n° 03-15 du 10 décembre 2015 portant cahier de charges des teneurs de
compte conservateurs.</p>

<h2>Article 15. Modification de la convention</h2>
<p>L'évolution des textes législatifs ou réglementaires peut modifier les clauses de la
Convention. Dans ce cas, les modifications prendront effet à la date d'application de ces
mesures sans intervention particulière du Teneur de compte à l'égard du Client, sauf
dispositions légales ou réglementaires particulières.</p>
<p>Le Teneur de compte se réserve également le droit de modifier les clauses, d'en ajouter
ou d'en supprimer. Les clauses de la Convention modifiées à la seule initiative du Teneur
de compte seront préalablement portées à la connaissance du Client, par tous moyens, deux
mois avant leur entrée en vigueur. L'acceptation de ces nouvelles conditions résultera de
la continuité du fonctionnement du compte-titres et de la poursuite, sans réserve, des
relations du Client avec le Teneur de compte.</p>

<h2>Article 16. Durée et résiliation de la convention</h2>
<p>Cette Convention est conclue pour une durée indéterminée. Elle peut être résiliée à tout
moment par chacune des parties dans les conditions ci-après exposées. En tout état de
cause, en cas d'inexécution par le Client ou le Teneur de compte de ses engagements, la
Convention peut être résiliée de plein droit sans mise en demeure à l'initiative de
l'autre partie.</p>
<p>La résiliation de la Convention entraîne de plein droit la clôture du compte-titres et
la résiliation des services qui lui sont rattachés. Cependant, les ordres ou transactions
en cours à cette date seront exécutés conformément à la Convention. Le Client est
toutefois avisé que toute demande de clôture de compte-titres (qu'elle soit initiée par
lui ou par le Teneur de compte) entraîne le blocage du compte-titres concerné, ce qui ne
permet plus au Client de passer de nouveaux ordres.</p>
<p>En cas d'ouverture d'une procédure collective d'apurement du passif d'un teneur de
compte ou de retrait de son agrément, la BEAC désigne un autre teneur de compte auprès
duquel les titres de la clientèle sont transférés ; les propriétaires de titres peuvent
ensuite les transférer au teneur de compte de leur choix. En cas de résiliation de la
Convention, le Client devra indiquer à la Banque les coordonnées du compte-titres vers
lequel les titres devront être transférés.</p>
<h3>16.1 Résiliation à l'initiative du teneur de compte</h3>
<p>La résiliation par le Teneur de compte s'effectuera moyennant un préavis de trente jours
calendaires et l'envoi préalable d'une lettre recommandée avec accusé de réception. Un
compte-titres sans titres, pendant une durée d'un an, est clôturé sans préavis. La clôture
du compte espèces associé entraîne automatiquement la clôture du (des) compte(s)-titres
associé(s).</p>
<h3>16.2 Résiliation à l'initiative du client</h3>
<p>La résiliation prend effet dès réception de la demande écrite du Client. Les titres
financiers seront soit liquidés sur instruction du Client, soit virés au compte-titres que
le Client a indiqué.</p>
<h3>16.3 Décès du client</h3>
<p>Le décès du Client titulaire d'un compte-titres individuel n'entraîne pas la clôture du
compte-titres mais son blocage. La clôture intervient à l'issue des opérations de
liquidation de la succession.</p>
<h4>16.3.1 Cas particulier du décès d'un des co-titulaires d'un compte-titres joint</h4>
<p>En cas de décès de l'un des co-titulaires, le compte-titres ne sera pas bloqué : il
continuera de fonctionner sous la signature du survivant ou de l'un ou l'autre des
survivants. Si la solidarité active permet au survivant, en cas de décès de l'un des
co-titulaires, d'appréhender l'actif qui figure au compte-titres, il convient de préciser
que le survivant (ou les survivants) est (sont) seul(s) comptable(s) de cet actif
vis-à-vis des héritiers du défunt ou de leur notaire, et qu'il(s) doit (doivent) leur
rendre des comptes. Le co-titulaire survivant ne peut exercer les droits extra-pécuniaires
attachés aux titres que s'il est le premier nommé dans l'intitulé du compte-titres ou s'il
a été spécialement désigné à cet effet. L'opposition écrite à la poursuite du
fonctionnement du compte-titres sous la signature du ou de l'un ou l'autre des survivants,
de la part d'un ayant droit du titulaire décédé justifiant de sa qualité ou du notaire
chargé de la succession, produira les mêmes effets que la dénonciation de solidarité : le
compte-titres sera transformé en compte-titres indivis.</p>
<h4>16.3.2 Cas d'un compte-titres indivis</h4>
<p>Le décès de l'un des co-indivisaires entraîne le blocage du compte. Le déblocage est
effectué à l'issue des opérations de liquidation de la succession. Dans tous les cas, les
co-indivisaires doivent maintenir au crédit de leur compte espèces associé une provision
suffisante et disponible permettant le règlement des opérations de débit en cours.</p>

<h2>Article 17. Droit applicable et langue de communication</h2>
<p>La Convention est soumise au droit camerounais. La langue de communication entre le
Client et la Banque, ainsi que celle employée dans les documents et informations
communiqués au Client, est soit l'anglais, soit le français, y compris les informations
précontractuelles. Tous les litiges nés de l'interprétation du présent contrat seront
tranchés par les tribunaux dans le ressort desquels le Teneur de compte a son siège.</p>

<h2>Article 18. Conditions financières</h2>
<p>Tout titulaire du compte-titres à Afriland First Bank est tenu de s'acquitter des frais
et commissions contenus dans les conditions générales de banque publiées sur le site
www.afrilandfirstbank.com ou remises à tout client qui en fait la demande.</p>

<hr>
<p>Fait en deux exemplaires à …………………………, le …………………………</p>
<p><strong>Le Client</strong> — faire précéder la signature de la mention « lu et approuvé ».</p>
<p><strong>Pour le Teneur de compte</strong> — faire précéder la signature de la mention « lu et approuvé ».</p>
$conv$,
    TRUE
);
