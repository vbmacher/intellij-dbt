// Run with NODE_PATH pointing to an installed Playwright package and DBT_CHROMIUM set when needed.
const {test, after, before} = require('node:test');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.DBT_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/lineage');
let browser, page, errors=[];
const fixture = {canGenerateDocs:true,focus:'model',nodes:[{id:'source',name:'events',kind:'source_table',package:'analytics',schema:'raw'},{id:'model',name:'orders',kind:'model',package:'analytics',schema:'mart',focus:true,materialized:'incremental'},{id:'report',name:'report',kind:'model',package:'analytics'},{id:'other',name:'unrelated',kind:'seed',package:'analytics'}],edges:[{source:'source',target:'model'},{source:'model',target:'report'}],boundary:[{direction:'upstream',count:7,anchors:['source']}],direction:'LR'};
before(async()=>{
 browser=await chromium.launch({headless:true,executablePath:process.env.DBT_CHROMIUM, args:['--no-sandbox']});
 page=await browser.newPage({viewport:{width:1400,height:850}});page.on('pageerror',e=>errors.push(e.message));
 const scripts=['cytoscape.min.js','dagre.min.js','cytoscape-dagre.min.js'].map(x=>'<script>'+fs.readFileSync(path.join(root,'vendor',x),'utf8').replaceAll('</script','<\\/script')+'</script>').join('');
 const html=fs.readFileSync(path.join(root,'index.html'),'utf8').replace('<!-- SCRIPTS -->',()=>scripts+'<script>window.events=[];window.dbtSend=x=>window.events.push(JSON.parse(x));</script>');
 await page.setContent(html); await page.evaluate(p=>window.dbtUpdate(p),fixture);
});
after(async()=>{await browser.close()});
const update=payload=>page.evaluate(p=>dbtUpdate(p),payload);
const lastExport=id=>page.evaluate(id=>events.filter(e=>e.type==='export'&&e.id===id).at(-1).value,id);
const exportedSvg=async()=>Buffer.from((await lastExport('svg')).split(',')[1],'base64').toString('latin1');
const hasEvent=(type,id)=>page.evaluate(([type,id])=>events.some(e=>e.type===type&&e.id===id),[type,id]);
async function nodePosition(id){return page.evaluate(id=>{const p=cy.getElementById(id).renderedPosition(),r=document.getElementById('cy').getBoundingClientRect();return{x:r.x+p.x,y:r.y+p.y}},id)}
test('bundled graph loads and cards show resource identity',async()=>{assert.deepEqual(errors,[]);assert.equal(await page.locator('.card:not(.boundary)').count(),4);assert.match(await page.locator('#cards').innerText(),/orders/)});
test('resource hover does not show documentation or a node tooltip',async()=>{
 const before=await page.evaluate(()=>events.filter(e=>e.type==='select').length),p=await nodePosition('source');
 await page.mouse.move(p.x,p.y);await page.waitForTimeout(230);
 assert.equal(await page.locator('#docs').isVisible(),false);
 assert.equal(await page.locator('#tooltip,.card[title]').count(),0);
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='select').length),before);
 await page.mouse.move(10,800);
});
test('single click selects docs and dims unrelated resources without focusing',async()=>{const p=await nodePosition('model');await page.mouse.click(p.x,p.y);await page.waitForTimeout(230);assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='focus').length),0);assert.ok(await hasEvent('select','model'));assert.equal(await page.locator('.card.dim').count(),1);assert.equal(await page.locator('#docs').isVisible(),true);await page.locator('#close-docs').click()});
test('button tooltips appear on hover and keyboard focus inside the panel',async()=>{
 for(const [name,text]of [['Zoom in','Zoom in'],['Open Documentation','Show documentation on the right for the selected resource'],['Regenerate Docs','Configure Generate Docs for this execution project']]){
  const button=page.getByRole('button',{name,exact:true});
  await button.hover();assert.equal(await page.getByRole('tooltip').isVisible(),true);
  assert.equal(await page.getByRole('tooltip').innerText(),text);
  const box=await page.getByRole('tooltip').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=1400);
  await page.mouse.move(5,840);assert.equal(await page.getByRole('tooltip').isVisible(),false);
 }
 const regenerate=page.getByRole('button',{name:'Regenerate Docs',exact:true});
 await regenerate.focus();assert.equal(await page.getByRole('tooltip').innerText(),'Configure Generate Docs for this execution project');
 assert.equal(await page.getByRole('tooltip').isVisible(),true);
 await regenerate.evaluate(button=>button.blur());assert.equal(await page.getByRole('tooltip').isVisible(),false);
});
test('Regenerate Docs requests generation and is disabled while a command runs',async()=>{
 const count=await page.evaluate(()=>events.filter(e=>e.type==='regenerate').length);
 await page.getByRole('button',{name:'Regenerate Docs',exact:true}).click();
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='regenerate').length),count+1);
 await page.evaluate(()=>dbtRunnerState(true,'Generating docs'));
 assert.equal(await page.locator('#regenerate').isDisabled(),true);
 await page.evaluate(()=>dbtRunnerState(false,'Docs ready'));
 assert.equal(await page.locator('#regenerate').isEnabled(),true);
});
test('Regenerate Docs requires an an identified execution project',async()=>{
 await update({...fixture,canGenerateDocs:false});
 assert.equal(await page.locator('#regenerate').isDisabled(),true);
 await page.evaluate(()=>dbtRunnerState(false,'Choose an execution project'));
 assert.equal(await page.locator('#regenerate').isDisabled(),true);
 await update(fixture);
 assert.equal(await page.locator('#regenerate').isEnabled(),true);
});
test('zoom, fit and search controls work',async()=>{const zoom=await page.evaluate(()=>cy.zoom());await page.locator('#zoom-in').click();assert.ok(await page.evaluate(()=>cy.zoom())>zoom);await page.locator('#fit').click();await page.locator('#search').fill('orders');assert.equal(await page.locator('.card:not(.dim):not(.boundary)').count(),1);await page.locator('#clear-search').click()});
test('viewport survives same-focus artifact and theme refresh',async()=>{await page.evaluate(()=>{cy.zoom(.75);cy.pan({x:123,y:65})});await update({...fixture,theme:{bg:'#fafafa',fg:'#222222',panel:'#eeeeee'}});assert.equal(await page.evaluate(()=>cy.zoom()),.75);assert.deepEqual(await page.evaluate(()=>cy.pan()),{x:123,y:65});assert.equal(await page.locator('body').evaluate(e=>getComputedStyle(e).backgroundColor),'rgb(250, 250, 250)')});
test('mouse wheel zoom responds promptly in both directions and keeps the cursor anchored',async()=>{
 const original=await page.evaluate(()=>({zoom:cy.zoom(),pan:cy.pan()}));
 try{
  await page.evaluate(()=>{cy.zoom(.75);cy.pan({x:123,y:65})});
  const box=await page.locator('#cy').boundingBox(),cursor={x:Math.floor(box.width*.4),y:Math.floor(box.height*.5)};
  await page.mouse.move(box.x+cursor.x,box.y+cursor.y);
  const anchor=()=>page.evaluate(p=>({x:(p.x-cy.pan().x)/cy.zoom(),y:(p.y-cy.pan().y)/cy.zoom()}),cursor);
  const before=await anchor();
  async function scroll(delta){for(let i=0;i<8;i++){const zoom=await page.evaluate(()=>cy.zoom());await page.mouse.wheel(0,delta);await page.waitForFunction(z=>cy.zoom()!==z,zoom)}}
  await scroll(-120);const zoomed=await page.evaluate(()=>cy.zoom());
  assert.ok(zoomed>=.75*1.2,'Eight wheel notches should increase magnification by at least 20%');
  await scroll(120);assert.ok(await page.evaluate(()=>cy.zoom())<=zoomed*.9,'Scrolling back should reduce magnification promptly');
  const after=await anchor();assert.ok(Math.abs(after.x-before.x)<1e-6&&Math.abs(after.y-before.y)<1e-6);
 }finally{await page.evaluate(v=>{cy.zoom(v.zoom);cy.pan(v.pan)},original)}
});
test('boundary click expands without refocusing; double click focuses',async()=>{await page.locator('#fit').click();const p=await nodePosition('boundary:upstream');await page.mouse.click(p.x,p.y);await page.waitForTimeout(230);assert.ok(await hasEvent('expand','upstream'));const n=await nodePosition('report');await page.mouse.dblclick(n.x,n.y,{delay:80});await page.waitForTimeout(230);assert.ok(await hasEvent('focus','report'))});
test('drag moves a card without navigation and background clears dimming',async()=>{const p=await nodePosition('model');const before=await page.evaluate(()=>events.filter(e=>e.type==='focus').length);await page.mouse.move(p.x,p.y);await page.mouse.down();await page.mouse.move(p.x+45,p.y+70,{steps:8});await page.mouse.up();await page.waitForTimeout(230);assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='focus').length),before);await page.mouse.click(10,800);assert.equal(await page.locator('.card.dim').count(),0)});
test('every direction renders without script errors',async()=>{for(const direction of ['LR','TB','RL','BT'])await update({...fixture,direction});assert.deepEqual(errors,[])});
test('schema, folder and tag grouping collapse without changing canonical resource identities',async()=>{
 const grouped={...fixture,nodes:fixture.nodes.map((n,i)=>({...n,folder:i<2?'models/staging':'models/marts',tags:i<2?['zebra','finance']:[]}))};
 await update(grouped);

 await page.locator('#group-by').selectOption('schema');
 assert.equal(await page.evaluate(()=>cy.nodes().filter(n=>n.data('group')).length),3);
 assert.equal(await page.evaluate(()=>cy.getElementById('model').data('parent')),'group:schema:mart');
 await page.evaluate(()=>select('model',false));
 await page.getByRole('button',{name:'▾ mart (1)',exact:true}).click();
 assert.equal(await page.evaluate(()=>cy.getElementById('model').length),0);
 assert.equal(await page.locator('.card.collapsed').count(),1);
 assert.equal(await page.evaluate(()=>selected),'');
 assert.ok(await page.evaluate(()=>cy.edges().some(e=>e.target().id()==='group:schema:mart')));
 await page.getByRole('button',{name:'▸ mart (1)',exact:true}).focus();await page.keyboard.press('Enter');
 assert.equal(await page.evaluate(()=>cy.getElementById('model').length),1);

 await page.locator('#group-by').selectOption('folder');
 assert.equal(await page.evaluate(()=>cy.getElementById('model').data('parent')),'group:folder:models%2Fstaging');

 await page.locator('#group-by').selectOption('tag');
 assert.equal(await page.evaluate(()=>cy.getElementById('model').data('parent')),'group:tag:finance');
 assert.equal(await page.evaluate(()=>cy.nodes().filter(n=>n.id()==='model').length),1);

 await page.locator('#group-by').selectOption('');await update(fixture);
});
test('selector filtering reuses backend resolution, marks adjacent boundaries and rejects stale results',async()=>{

 await page.locator('#selector').fill('tag:finance+');await page.locator('#exclude').fill('tag:deprecated');
 await page.getByRole('button',{name:'Apply filter',exact:true}).click();
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='filter'&&e.id==='tag:finance+'&&e.value==='tag:deprecated')));
 await page.evaluate(()=>dbtShowFilter({expression:'old',exclude:'',ids:[]}));
 assert.equal(await page.locator('.card:not(.boundary)').count(),4);
 await page.evaluate(()=>dbtShowFilter({expression:'tag:finance+',exclude:'tag:deprecated',ids:['model'],diagnostics:[]}));
 assert.equal(await page.locator('.card:not(.boundary)').count(),3);
 assert.equal(await page.locator('.card.filtered-boundary').count(),2);
 assert.match(await page.locator('#filter-status').innerText(),/1 matches · 2 boundary · 1 hidden/);
 assert.equal(await page.evaluate(()=>cy.getElementById('other').length),0);
 await page.getByRole('button',{name:'Clear filter',exact:true}).click();assert.equal(await page.locator('.card:not(.boundary)').count(),4);
});
test('pin focus sends the current identity and survives backend refresh',async()=>{
 await page.locator('#pin-focus').click();assert.equal(await page.locator('#pin-focus').getAttribute('aria-pressed'),'true');
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='pin'&&e.id==='model'&&e.value==='true')));
 await update({...fixture,pinned:true});assert.equal(await page.locator('#pin-focus').getAttribute('aria-label'),'Unpin focus');
 await page.locator('#pin-focus').click();assert.ok(await page.evaluate(()=>events.some(e=>e.type==='pin'&&e.value==='false')));
});
test('minimap click, keyboard and toggle navigate the existing viewport',async()=>{
 await page.waitForFunction(()=>document.querySelectorAll('#minimap rect').length>1);
 const before=await page.evaluate(()=>cy.pan()),box=await page.locator('#minimap').boundingBox();
 await page.mouse.click(box.x+20,box.y+20);assert.notDeepEqual(await page.evaluate(()=>cy.pan()),before);
 await page.locator('#minimap').focus();const pan=await page.evaluate(()=>cy.pan());await page.keyboard.press('ArrowRight');assert.equal((await page.evaluate(()=>cy.pan())).x,pan.x-60);

 await page.locator('#show-minimap').uncheck();assert.equal(await page.locator('#minimap').isVisible(),false);

 await page.locator('#show-minimap').check();assert.equal(await page.locator('#minimap').isVisible(),true);await page.locator('#fit').click();
});
test('SVG, PNG and clipboard exports include local visible labels, Unicode, theme and optional legend',async()=>{
 await update({...fixture,nodes:fixture.nodes.map(n=>n.id==='model'?{...n,name:'objednávky 日本語 & <orders>'}:n)});

 await page.locator('#selector').fill('orders');await page.evaluate(()=>dbtShowFilter({expression:'orders',exclude:'',ids:['model'],diagnostics:[]}));

 await page.locator('#export-svg').click();
 const svg=await exportedSvg();
 const decoded=Buffer.from(svg,'binary').toString('utf8');
 assert.match(decoded,/objednávky 日本語 &amp; &lt;orders&gt;/);assert.doesNotMatch(decoded,/unrelated/);assert.match(decoded,/downstream dependency/);
 assert.match(decoded,/#fafafa/);assert.doesNotMatch(decoded,/var\(--/);assert.doesNotMatch(decoded,/<script|https:|foreignObject/);

 await page.locator('#export-legend').uncheck();
 await page.locator('#export-svg').click();
 assert.doesNotMatch(await exportedSvg(),/downstream dependency/);

 await page.locator('#export-png').click();await page.waitForFunction(()=>events.some(e=>e.type==='export'&&e.id==='png'));
 const png=await lastExport('png');assert.ok(png.startsWith('data:image/png;base64,iVBOR'));

 await page.locator('#copy-image').click();await page.waitForFunction(()=>events.some(e=>e.type==='export'&&e.id==='clipboard'));assert.ok((await lastExport('clipboard')).startsWith('data:image/png;base64,'));

 await page.locator('#export-legend').check();
 await page.locator('#clear-filter').click();await update(fixture);assert.deepEqual(errors,[]);
});
test('grouping preserves package and version collisions on representative large graphs',async()=>{
 const nodes=Array.from({length:180},(_,i)=>({id:'model:package'+(i%3)+':orders:v'+i,name:'orders',package:'package'+(i%3),kind:'model',schema:'schema'+(i%6),tags:['shared'],folder:'models/'+(i%10)}));
 const edges=nodes.slice(1).map((n,i)=>({source:nodes[i].id,target:n.id}));
 await update({...fixture,focus:nodes[0].id,nodes,edges,boundary:[]});

 await page.locator('#group-by').selectOption('schema');assert.equal(await page.locator('.card:not(.boundary)').count(),180);
 assert.equal(await page.evaluate(()=>cy.nodes().filter(n=>n.data('group')).length),6);

 await page.locator('#group-by').selectOption('tag');assert.equal(await page.locator('.card:not(.boundary)').count(),180);
 await page.getByRole('button',{name:'▾ shared (180)',exact:true}).click();assert.equal(await page.locator('.card.collapsed').count(),1);
 await page.getByRole('button',{name:'▸ shared (180)',exact:true}).click();assert.equal(await page.locator('.card:not(.boundary)').count(),180);

 await page.locator('#group-by').selectOption('');await update(fixture);assert.deepEqual(errors,[]);
});
test('execution badges expose provenance and route failed tests without relying on colors',async()=>{
 const execution={status:'failed',label:'Build failed',failingTests:2,details:'target: dev; invocation: run-1; run_results.json'};
 await page.evaluate(({fixture,execution})=>dbtUpdate({...fixture,nodes:fixture.nodes.map(n=>({...n,execution:n.id==='model'?execution:{status:'unknown',label:'Not run / result unavailable',failingTests:0,details:'No compatible result artifact'}}))}),{fixture,execution});
 const badge=page.getByRole('button',{name:/orders: 2 failing tests · Build failed/});assert.equal(await badge.count(),1);assert.match(await badge.getAttribute('aria-label'),/run_results.json/);
 await badge.click();assert.ok(await hasEvent('execution','model'));


 await page.locator('#export-svg').click();assert.match(await exportedSvg(),/Build failed/);
 await update({...fixture,nodes:fixture.nodes.map(n=>({...n,execution:{status:'unknown',label:'Not run / result unavailable',failingTests:0,details:'Artifact no longer compatible'}}))});
 assert.equal(await page.getByRole('button',{name:/Build failed/}).count(),0);
 assert.equal(await page.locator('.execution-badge[data-status=unknown]').count(),0);
 await update(fixture);
});
test('exports support every layout direction without invalid geometry',async()=>{
 for(const direction of ['LR','TB','RL','BT']){
  await update({...fixture,direction});
  const svg=await page.evaluate(()=>graphSvg());assert.doesNotMatch(svg,/NaN|undefined/);
 }
 assert.deepEqual(errors,[]);
});
test('test-only failure counts are visible and remain accurate across theme and evidence refresh',async()=>{
 const execution={status:'unknown',label:'Not run / result unavailable',failingTests:3,details:'prod · run-test-only · run_results.json'};
 const payload={...fixture,nodes:fixture.nodes.map(n=>n.id==='model'?{...n,execution}:n)};
 await update(payload);
 const badge=page.getByRole('button',{name:/orders: 3 failing tests/});
 assert.match(await badge.innerText(),/^3 failing tests/);
 assert.match(await badge.getAttribute('aria-label'),/prod.*run-test-only/);
 await badge.focus();await page.keyboard.press('Enter');
 assert.ok(await hasEvent('execution','model'));
 for(const theme of [{bg:'#202124',fg:'#dfe1e5',panel:'#2b2d30'},{bg:'#fafafa',fg:'#222222',panel:'#eeeeee'}]){
  await update({...payload,theme});
  assert.equal(await page.getByRole('button',{name:/3 failing tests/}).count(),1);
  assert.match(await page.evaluate(()=>graphSvg()),/3 failing tests/);
 }
 await update({...payload,nodes:payload.nodes.map(n=>n.execution?{...n,execution:{...n.execution,failingTests:0}}:n)});
 assert.equal(await page.getByRole('button',{name:/failing tests/}).count(),0);
 await update(fixture);assert.deepEqual(errors,[]);
});

test('Explicit configuration selection updates Lineage fields while stale local replies are rejected',async()=>{
 await update(fixture);

 await page.locator('#selector').fill('old');await page.locator('#exclude').fill('tag:old');
 await page.evaluate(()=>dbtShowFilter({fromConfiguration:true,expression:'fqn:analytics.orders',exclude:'',ids:['model'],diagnostics:[]}));
 assert.equal(await page.locator('#selector').inputValue(),'fqn:analytics.orders');
 assert.equal(await page.locator('#exclude').inputValue(),'');
 assert.match(await page.locator('#filter-status').innerText(),/1 matches/);
 await page.evaluate(()=>dbtShowFilter({expression:'old',exclude:'tag:old',ids:['other'],diagnostics:[]}));
 assert.equal(await page.locator('#selector').inputValue(),'fqn:analytics.orders');
 assert.match(await page.locator('#filter-status').innerText(),/1 matches/);
 await page.getByRole('button',{name:'Clear filter',exact:true}).click();
});

test('Lineage filters remain independent and only Use in Run Configuration requests a handoff',async()=>{

 await page.locator('#selector').fill('tag:finance+');await page.locator('#exclude').fill('');
 await page.locator('#filter-form').evaluate(form=>form.requestSubmit());
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='filter').at(-1).shared),undefined);
 await page.locator('#use-config').click();assert.equal(await page.evaluate(()=>events.at(-1).type),'use-configuration-selection');
 await page.evaluate(()=>dbtShowFilter({expression:'tag:finance+',exclude:'',ids:['model'],diagnostics:[]}));
 await update(fixture);
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='filter').at(-1).shared),undefined);
 await page.getByRole('button',{name:'Clear filter',exact:true}).click();
 assert.deepEqual(await page.evaluate(()=>events.filter(e=>e.type==='filter').at(-1)),{type:'filter',id:'',value:''});
});


test('lineage actions stay flat and fields remain visible at narrow widths',async()=>{
 try{
  for(const width of [600,1400]){
   await page.setViewportSize({width,height:850});
   assert.equal(await page.locator('#graph-controls details').count(),0);
   for(const id of ['selector','exclude','group-by','show-minimap','export-legend','resource-picker'])
    assert.equal(await page.locator('#'+id).isVisible(),true);
   for(const name of ['Apply filter','Clear filter','Use in Run Configuration','Export PNG','Export SVG','Copy image']){
    const button=page.getByRole('button',{name,exact:true});
    assert.equal(await button.isVisible(),true);
    const box=await button.boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width);
    assert.equal(await button.evaluate(e=>e.closest('details,[popover]')),null);
   }
   for(const id of ['apply-filter','clear-filter','use-config'])assert.equal(await page.locator('#'+id).evaluate(e=>e.closest('form')?.id),'filter-form');
   const graph=await page.locator('#main').boundingBox();
   assert.ok(graph.height>430,'The graph retains usable space with visible controls');
  }
 }finally{await page.setViewportSize({width:1400,height:850})}
});

test('resource cards hide unresolved configuration and unknown result badges, with keyboard inspection',async()=>{
 const payload={...fixture,nodes:fixture.nodes.map(n=>n.id==='model'?{...n,materialized:"{{ config.get('materialized') }}",schema:'null',execution:{status:'unknown',label:'Not run / result unavailable',failingTests:0,details:'Missing artifact'}}:n)};
 await update(payload);
 const card=page.locator('.card[data-kind=model]').filter({hasText:'orders'});
 assert.doesNotMatch(await card.innerText(),/\{\{|null|Not run/);
 assert.equal(await page.locator('.execution-badge').count(),0);
 assert.match(await card.getAttribute('aria-label'),/graph focus/);
 await page.locator('#resource-picker').selectOption('model');
 assert.ok(await hasEvent('select','model'));
 await card.focus();await page.keyboard.press('Enter');
 assert.equal(await page.locator('.card.selected').count(),1);
 assert.match(await page.locator('#selection-context').innerText(),/Focus: orders · Selected: orders/);
 await update(fixture);
});

test('documentation requests are explicit and refreshes do not reopen documentation',async()=>{
 await update(fixture);
 await page.evaluate(()=>{events=[];select('model',false)});
 await page.getByRole('button',{name:'Open Documentation',exact:true}).click();
 assert.deepEqual(await page.evaluate(()=>events.at(-1)),{type:'select',id:'model',value:''});
 await page.evaluate(()=>events=[]);
 await update(fixture);
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='select').length),0);
 assert.equal(await page.locator('#docs').isVisible(),true);
 await page.locator('#close-docs').click();await update(fixture);
 assert.equal(await page.locator('#docs').isVisible(),false);
 assert.deepEqual(errors,[]);
});


test('documentation stays on the right, resizes horizontally and ignores obsolete replies',async()=>{
 await update(fixture);await page.evaluate(()=>select('model'));
 await page.evaluate(()=>dbtShowDocumentation({id:'model',column:'',html:'<b>analytics.orders</b><p>Orders and amounts</p><a href="dbt-column:order+id">order id</a>'}));
 assert.match(await page.locator('#docs').innerText(),/analytics.orders/);
 const pane=await page.locator('#docs').boundingBox(),graph=await page.locator('#graph-pane').boundingBox();
 assert.equal(pane.x+pane.width,1400);assert.ok(graph.x+graph.width<=pane.x);
 const divider=await page.locator('#docs-divider').boundingBox();
 await page.mouse.move(divider.x+3,divider.y+30);await page.mouse.down();await page.mouse.move(divider.x-100,divider.y+30,{steps:5});await page.mouse.up();
 const wider=await page.locator('#docs').boundingBox();assert.ok(wider.width>pane.width+90);assert.equal(wider.x+wider.width,1400);
 assert.ok(await page.evaluate(()=>Math.abs(cy.width()-document.getElementById('graph-pane').clientWidth)<1));
 await page.locator('#docs-divider').focus();await page.keyboard.press('ArrowRight');
 assert.equal(Math.round((await page.locator('#docs').boundingBox()).width),Math.round(wider.width)-20);
 await page.getByRole('link',{name:'order id',exact:true}).click();
 assert.deepEqual(await page.evaluate(()=>events.at(-1)),{type:'column',id:'model',value:'order id'});
 await page.evaluate(()=>dbtShowDocumentation({id:'model',column:'order id',html:'<b>orders.order id</b><h3>Upstream dependencies</h3>'}));
 assert.match(await page.locator('#docs').innerText(),/orders.order id/);
 await page.getByRole('button',{name:'Resource details',exact:true}).click();
 assert.deepEqual(await page.evaluate(()=>events.at(-1)),{type:'select',id:'model',value:''});
 await page.evaluate(()=>select('source'));
 await page.evaluate(()=>dbtShowDocumentation({id:'model',column:'',html:'Obsolete orders'}));
 assert.equal(await page.locator('#docs-content').innerText(),'Loading documentation…');
 await page.evaluate(()=>dbtShowDocumentation({id:'source',column:'',html:'<b>analytics.events</b>'}));
 await page.mouse.move(20,400);assert.equal((await page.locator('#docs').boundingBox()).x+(await page.locator('#docs').boundingBox()).width,1400);
 await update({...fixture,theme:{bg:'#fafafa',fg:'#222222',panel:'#eeeeee'}});
 assert.match(await page.locator('#docs').innerText(),/analytics.events/);
 for(const width of [600,1400]){
  await page.setViewportSize({width,height:850});
  const bounds=await page.locator('#docs').boundingBox(),canvas=await page.locator('#graph-pane').boundingBox();
  assert.equal(bounds.x+bounds.width,width);assert.ok(canvas.width>=120);
  await page.locator('#group-by').selectOption('schema');
  const groupedDocs=await page.locator('#docs').boundingBox(),groupedCanvas=await page.locator('#graph-pane').boundingBox();
  assert.equal(groupedDocs.x+groupedDocs.width,width);assert.ok(groupedCanvas.width>=120);
  await page.locator('#group-by').selectOption('');
 }
 await page.locator('#close-docs').click();
 await page.evaluate(()=>dbtShowDocumentation({id:'source',column:'',html:'Late reply'}));
 assert.equal(await page.locator('#docs').isVisible(),false);await update(fixture);
 assert.deepEqual(errors,[]);
});

test('large graphs stay readable and group controls never cover the canvas',async()=>{
 const nodes=Array.from({length:300},(_,i)=>({id:'large:'+i,name:'customer_orders_'+i,kind:'model',package:'analytics',folder:'models/product_metrics/marts/region_'+(i%30)}));
 const edges=nodes.flatMap((n,i)=>i%6?[{source:nodes[i-1].id,target:n.id}]:[]);
 const payload={...fixture,focus:'',nodes,edges,boundary:[]};
 try{
  for(const width of [600,1600]){
   await page.setViewportSize({width,height:500});
   await update(payload);
   assert.ok(await page.evaluate(()=>cy.zoom())>=.8);
   await page.locator('#group-by').selectOption('folder');
   const groups=await page.locator('#groups').boundingBox(),graph=await page.locator('#graph-pane').boundingBox();
   assert.ok(groups.x+groups.width<=graph.x,'Groups occupy their own panel');
   assert.ok(graph.height>=260,'A short tool window retains a usable canvas');
   assert.ok(await page.locator('#groups').evaluate(e=>e.scrollHeight>e.clientHeight),'Long group lists scroll');
   assert.ok(await page.evaluate(()=>cy.zoom())>=.8);
   const firstCard=await page.locator('.card').first().boundingBox();
   assert.ok(firstCard.width>=200&&firstCard.x>=graph.x&&firstCard.y>=graph.y);

   const lastGroup=page.locator('#groups button').last();
   await lastGroup.click();
   assert.ok(await page.locator('#groups').evaluate(e=>e.scrollTop)>0,'Collapsing preserves the group list position');
   assert.ok(await page.evaluate(()=>cy.zoom())>=.8);
   await page.locator('#group-by').selectOption('');
  }

  await page.locator('#fit').click();
  const overview=await page.evaluate(()=>{const b=cy.elements().renderedBoundingBox();return{zoom:cy.zoom(),b,w:cy.width(),h:cy.height()}});
  assert.ok(overview.zoom<.8,'Explicit Fit graph provides an overview');
  assert.ok(overview.b.x1>=0&&overview.b.y1>=0&&overview.b.x2<=overview.w&&overview.b.y2<=overview.h,'The overview fits the full graph');
  assert.equal(await page.locator('#reset-zoom').innerText(),Math.round(overview.zoom*100)+'%');

  await page.locator('#resource-picker').selectOption('large:299');
  const inspected=await nodePosition('large:299'),pane=await page.locator('#graph-pane').boundingBox();
  assert.equal(await page.evaluate(()=>cy.zoom()),1);
  assert.ok(Math.abs(inspected.x-pane.x-pane.width/2)<1&&Math.abs(inspected.y-pane.y-pane.height/2)<1);
  await page.locator('#zoom-out').click();await page.locator('#reset-zoom').click();
  assert.equal(await page.evaluate(()=>cy.zoom()),1);
  assert.equal(await page.locator('#reset-zoom').innerText(),'100%');

  await page.locator('#search').fill('customer_orders');
  assert.ok(await page.evaluate(()=>cy.zoom())>=.8,'Broad searches retain readable labels');
  await page.locator('#selector').fill('customer_orders');
  await page.evaluate(ids=>dbtShowFilter({expression:'customer_orders',exclude:'',ids,diagnostics:[]}),nodes.map(n=>n.id));
  assert.ok(await page.evaluate(()=>cy.zoom())>=.8,'Selector changes retain readable labels');
  await update({...payload,focus:'large:150',nodes:payload.nodes.map(n=>({...n,focus:n.id==='large:150'}))});
  const focused=await nodePosition('large:150');
  assert.ok(Math.abs(focused.x-pane.x-pane.width/2)<1&&Math.abs(focused.y-pane.y-pane.height/2)<1,'New editor focus is centered');
  assert.deepEqual(errors,[]);
 }finally{
  await page.locator('#clear-search').click();await page.locator('#clear-filter').click();
  await page.locator('#group-by').selectOption('');
  await page.setViewportSize({width:1400,height:850});await update(fixture);
 }
});

test('native icon refresh preserves visible action labels and clearing selection updates context',async()=>{
 const image='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXioAAAAASUVORK5CYII=';
 await page.evaluate(({fixture,image})=>dbtUpdate({...fixture,icons:Object.fromEntries(['fit','apply-filter','clear-filter','use-config','export-png','export-svg','zoom-in'].map(id=>[id,image]))}),{fixture,image});
 for(const [id,text]of [['fit','Fit graph'],['apply-filter','Apply'],['clear-filter','Clear'],['use-config','Run configuration…'],['export-png','PNG'],['export-svg','SVG']])assert.equal(await page.locator('#'+id).innerText(),text);
 assert.equal(await page.locator('#zoom-in img').count(),1);
 await page.locator('#resource-picker').selectOption('model');
 await page.mouse.click(10,820);
 assert.equal(await page.locator('#resource-picker').inputValue(),'');
 assert.doesNotMatch(await page.locator('#selection-context').innerText(),/Selected:/);
 assert.deepEqual(errors,[]);
});
