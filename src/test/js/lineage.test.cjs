// Run with NODE_PATH pointing to an installed Playwright package and DBT_CHROMIUM set when needed.
const {test, after, before} = require('node:test');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.DBT_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/lineage');
let browser, page, errors=[];
const fixture = {canGenerateDocs:true,focus:'model',nodes:[{id:'source',name:'events',kind:'source_table',package:'analytics',schema:'raw'},{id:'model',name:'orders',kind:'model',package:'analytics',schema:'mart',focus:true,materialized:'incremental'},{id:'report',name:'report',kind:'model',package:'analytics'},{id:'other',name:'unrelated',kind:'seed',package:'analytics'}],edges:[{source:'source',target:'model'},{source:'model',target:'report'}],boundary:[{direction:'upstream',count:7,anchors:['source']}],direction:'LR',edgeStyle:'round-taxi',docsVisible:true,docsWidth:360};
const doc={name:'analytics.orders',kind:'model',logical:'orders',description:'<script>bad()</script>\n'+ 'Long description. '.repeat(250),source:'/project/models/orders.sql',tags:['finance'],attributes:{materialized:'incremental',relation_name:'db.mart.orders'},columns:[{name:'id',type:'bigint',description:'Identifier',declarations:['unique']}],tests:[{name:'unique',column:'id'}],rawSql:'select '+ 'wide_column, '.repeat(80)+'id',compiledSql:null,upstream:['events'],downstream:['report']};
before(async()=>{
 browser=await chromium.launch({headless:true,executablePath:process.env.DBT_CHROMIUM, args:['--no-sandbox']});
 page=await browser.newPage({viewport:{width:1400,height:850}});page.on('pageerror',e=>errors.push(e.message));
 const scripts=['cytoscape.min.js','dagre.min.js','cytoscape-dagre.min.js'].map(x=>'<script>'+fs.readFileSync(path.join(root,'vendor',x),'utf8').replaceAll('</script','<\\/script')+'</script>').join('');
 const html=fs.readFileSync(path.join(root,'index.html'),'utf8').replace('<!-- SCRIPTS -->',()=>scripts+'<script>window.events=[];window.dbtSend=x=>window.events.push(JSON.parse(x));</script>');
 await page.setContent(html); await page.evaluate(p=>window.dbtUpdate(p),fixture);
});
after(async()=>{await browser.close()});
async function nodePosition(id){return page.evaluate(id=>{const p=cy.getElementById(id).renderedPosition(),r=document.getElementById('cy').getBoundingClientRect();return{x:r.x+p.x,y:r.y+p.y}},id)}
test('bundled graph loads and cards show resource identity',async()=>{assert.deepEqual(errors,[]);assert.equal(await page.locator('.card:not(.boundary)').count(),4);assert.match(await page.locator('#cards').innerText(),/orders/)});
test('resource hover shows available details',async()=>{const p=await nodePosition('source');await page.mouse.move(p.x,p.y);assert.match(await page.locator('#tooltip').innerText(),/analytics.events/);await page.mouse.move(10,800)});
test('single click selects docs and dims unrelated resources without focusing',async()=>{const p=await nodePosition('model');await page.mouse.click(p.x,p.y);await page.waitForTimeout(230);assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='focus').length),0);assert.ok(await page.evaluate(()=>events.some(e=>e.type==='select'&&e.id==='model')));assert.equal(await page.locator('.card.dim').count(),1);await page.evaluate(d=>dbtShowDoc('model',d),doc);assert.equal(await page.locator('#doc-name').innerText(),'analytics.orders');assert.match(await page.locator('#description').innerText(),/<script>/);assert.equal(await page.locator('#description script').count(),0);if(process.env.DBT_LINEAGE_SCREENSHOT)await page.screenshot({path:process.env.DBT_LINEAGE_SCREENSHOT})});
test('tabs retain declarations, SQL whitespace, missing compiled state, and full metadata',async()=>{await page.getByRole('button',{name:'Tests',exact:true}).click();assert.match(await page.locator('#doc-content').innerText(),/Declarations only/);await page.getByRole('button',{name:'SQL',exact:true}).click();assert.match(await page.locator('#doc-content').innerText(),/Compiled SQL unavailable/);assert.ok(await page.locator('pre').evaluate(e=>e.scrollWidth>e.clientWidth));await page.getByRole('button',{name:'Metadata',exact:true}).click();assert.match(await page.locator('#doc-content').innerText(),/Downstream/);await page.getByRole('button',{name:'Expand description'}).click();assert.equal(await page.locator('#description.expanded').count(),1)});
test('stale docs never overwrite the selected resource',async()=>{await page.evaluate(d=>dbtShowDoc('some-other-resource',{...d,name:'WRONG'}),doc);assert.equal(await page.locator('#doc-name').innerText(),'analytics.orders')});
test('documentation toggles and stays hidden across refreshes and model changes',async()=>{
 const toggle=page.getByRole('button',{name:'Toggle documentation',exact:true});
 await toggle.click();assert.equal(await page.locator('#docs').isVisible(),false);
 assert.equal(await toggle.getAttribute('aria-pressed'),'false');
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='docs'&&e.value==='false')));
 await page.evaluate(p=>dbtUpdate({...p,docsVisible:false}),fixture);
 assert.equal(await page.locator('#docs').isVisible(),false);
 await toggle.click();assert.equal(await page.locator('#docs').isVisible(),true);
 await toggle.click();assert.equal(await page.locator('#docs').isVisible(),false);
 await page.evaluate(p=>dbtUpdate({...p,focus:'report',docsVisible:false}),fixture);
 assert.equal(await page.locator('#docs').isVisible(),false);
 await page.evaluate(p=>dbtUpdate(p),fixture);await page.evaluate(d=>dbtShowDoc('model',d),doc);
});
test('documentation controls stay together on the right when the window narrows',async()=>{
 try{
  for(const width of [1400,600]){
   await page.setViewportSize({width,height:850});
   const status=await page.locator('#status').boundingBox(),toggle=await page.locator('#show-docs').boundingBox(),regenerate=await page.locator('#regenerate').boundingBox();
   assert.ok(toggle.x>=0);assert.ok(regenerate.x>toggle.x);
   assert.ok(regenerate.x+regenerate.width<=width);
   for(const name of ['Clear search','Zoom in','Zoom out','Fit graph','Toggle documentation','Regenerate Docs'])
    assert.ok(await page.getByRole('button',{name,exact:true}).getAttribute('data-tooltip'));
  }
 }finally{await page.setViewportSize({width:1400,height:850})}
});
test('button tooltips appear on hover and keyboard focus inside the panel',async()=>{
 for(const [name,text]of [['Zoom in','Zoom in'],['Toggle documentation','Show/hide documentation'],['Regenerate Docs','Generate Docs for the Runner selection'],['Columns','View documented columns']]){
  const button=page.getByRole('button',{name,exact:true});
  await button.hover();assert.equal(await page.getByRole('tooltip').isVisible(),true);
  assert.equal(await page.getByRole('tooltip').innerText(),text);
  const box=await page.getByRole('tooltip').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=1400);
  await page.mouse.move(5,840);assert.equal(await page.getByRole('tooltip').isVisible(),false);
 }
 const regenerate=page.getByRole('button',{name:'Regenerate Docs',exact:true});
 await regenerate.focus();assert.equal(await page.getByRole('tooltip').innerText(),'Generate Docs for the Runner selection');
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
test('Regenerate Docs requires an explicit Runner selection',async()=>{
 await page.evaluate(p=>dbtUpdate({...p,canGenerateDocs:false}),fixture);
 assert.equal(await page.locator('#regenerate').isDisabled(),true);
 await page.evaluate(()=>dbtRunnerState(false,'Choose a run selection'));
 assert.equal(await page.locator('#regenerate').isDisabled(),true);
 await page.evaluate(p=>dbtUpdate(p),fixture);
 assert.equal(await page.locator('#regenerate').isEnabled(),true);
});
test('hide, show, resize, zoom, fit and search controls work',async()=>{await page.getByRole('button',{name:'Close documentation'}).click();assert.equal(await page.locator('#docs').isVisible(),false);await page.getByRole('button',{name:'Toggle documentation',exact:true}).click();const handle=await page.locator('#handle').boundingBox();await page.mouse.move(handle.x+2,handle.y+100);await page.mouse.down();await page.mouse.move(handle.x-90,handle.y+100);await page.mouse.up();assert.ok((await page.locator('#docs').boundingBox()).width>400);const zoom=await page.evaluate(()=>cy.zoom());await page.locator('#zoom-in').click();assert.ok(await page.evaluate(()=>cy.zoom())>zoom);await page.locator('#fit').click();await page.locator('#search').fill('orders');assert.equal(await page.locator('.card:not(.dim):not(.boundary)').count(),1);await page.locator('#clear-search').click()});
test('viewport survives same-focus artifact and theme refresh',async()=>{await page.evaluate(()=>{cy.zoom(.75);cy.pan({x:123,y:65})});await page.evaluate(p=>dbtUpdate({...p,theme:{bg:'#fafafa',fg:'#222222',panel:'#eeeeee'}}),fixture);assert.equal(await page.evaluate(()=>cy.zoom()),.75);assert.deepEqual(await page.evaluate(()=>cy.pan()),{x:123,y:65});assert.equal(await page.locator('body').evaluate(e=>getComputedStyle(e).backgroundColor),'rgb(250, 250, 250)')});
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
test('boundary click expands without refocusing; double click focuses',async()=>{await page.locator('#fit').click();const p=await nodePosition('boundary:upstream');await page.mouse.click(p.x,p.y);await page.waitForTimeout(230);assert.ok(await page.evaluate(()=>events.some(e=>e.type==='expand'&&e.id==='upstream')));const n=await nodePosition('report');await page.mouse.dblclick(n.x,n.y,{delay:80});await page.waitForTimeout(230);assert.ok(await page.evaluate(()=>events.some(e=>e.type==='focus'&&e.id==='report')))});
test('drag moves a card without navigation and background clears dimming',async()=>{const p=await nodePosition('model');const before=await page.evaluate(()=>events.filter(e=>e.type==='focus').length);await page.mouse.move(p.x,p.y);await page.mouse.down();await page.mouse.move(p.x+45,p.y+70,{steps:8});await page.mouse.up();await page.waitForTimeout(230);assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='focus').length),before);await page.mouse.click(10,800);assert.equal(await page.locator('.card.dim').count(),0)});
test('every direction and edge style renders without script errors',async()=>{for(const direction of ['LR','TB','RL','BT'])for(const edgeStyle of ['bezier','taxi','round-taxi','segments','straight','unbundled-bezier','haystack'])await page.evaluate(p=>dbtUpdate(p),{...fixture,direction,edgeStyle});assert.deepEqual(errors,[])});
test('schema, folder and tag grouping collapse without changing canonical resource identities',async()=>{
 const grouped={...fixture,nodes:fixture.nodes.map((n,i)=>({...n,folder:i<2?'models/staging':'models/marts',tags:i<2?['zebra','finance']:[]}))};
 await page.evaluate(p=>dbtUpdate(p),grouped);
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#group-by').selectOption('schema');
 assert.equal(await page.evaluate(()=>cy.nodes().filter(n=>n.data('group')).length),3);
 assert.equal(await page.evaluate(()=>cy.getElementById('model').data('parent')),'group:schema:mart');
 await page.evaluate(()=>select('model',false));
 await page.getByRole('button',{name:'▾ mart (1)',exact:true}).click();
 assert.equal(await page.evaluate(()=>cy.getElementById('model').length),0);
 assert.equal(await page.locator('.card.collapsed').count(),1);
 assert.match(await page.locator('#doc-name').innerText(),/Selection no longer visible/);
 assert.ok(await page.evaluate(()=>cy.edges().some(e=>e.target().id()==='group:schema:mart')));
 await page.getByRole('button',{name:'▸ mart (1)',exact:true}).focus();await page.keyboard.press('Enter');
 assert.equal(await page.evaluate(()=>cy.getElementById('model').length),1);
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#group-by').selectOption('folder');
 assert.equal(await page.evaluate(()=>cy.getElementById('model').data('parent')),'group:folder:models%2Fstaging');
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#group-by').selectOption('tag');
 assert.equal(await page.evaluate(()=>cy.getElementById('model').data('parent')),'group:tag:finance');
 assert.equal(await page.evaluate(()=>cy.nodes().filter(n=>n.id()==='model').length),1);
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#group-by').selectOption('');await page.evaluate(p=>dbtUpdate(p),fixture);
});
test('selector filtering reuses backend resolution, marks adjacent boundaries and rejects stale results',async()=>{
 await page.locator('#filter-options').evaluate(e=>e.open=true);
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
 await page.locator('#filter-options').evaluate(e=>e.open=true);await page.getByRole('button',{name:'Clear filter',exact:true}).click();assert.equal(await page.locator('.card:not(.boundary)').count(),4);
});
test('pin focus sends the current identity and survives backend refresh',async()=>{
 await page.locator('#pin-focus').click();assert.equal(await page.locator('#pin-focus').getAttribute('aria-pressed'),'true');
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='pin'&&e.id==='model'&&e.value==='true')));
 await page.evaluate(p=>dbtUpdate({...p,pinned:true}),fixture);assert.equal(await page.locator('#pin-focus').innerText(),'Unpin focus');
 await page.locator('#pin-focus').click();assert.ok(await page.evaluate(()=>events.some(e=>e.type==='pin'&&e.value==='false')));
});
test('minimap click, keyboard and toggle navigate the existing viewport',async()=>{
 await page.waitForFunction(()=>document.querySelectorAll('#minimap rect').length>1);
 const before=await page.evaluate(()=>cy.pan()),box=await page.locator('#minimap').boundingBox();
 await page.mouse.click(box.x+20,box.y+20);assert.notDeepEqual(await page.evaluate(()=>cy.pan()),before);
 await page.locator('#minimap').focus();const pan=await page.evaluate(()=>cy.pan());await page.keyboard.press('ArrowRight');assert.equal((await page.evaluate(()=>cy.pan())).x,pan.x-60);
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#show-minimap').uncheck();assert.equal(await page.locator('#minimap').isVisible(),false);
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#show-minimap').check();assert.equal(await page.locator('#minimap').isVisible(),true);await page.locator('#fit').click();
});
test('SVG, PNG and clipboard exports include local visible labels, Unicode, theme and optional legend',async()=>{
 await page.evaluate(p=>dbtUpdate({...p,nodes:p.nodes.map(n=>n.id==='model'?{...n,name:'objednávky 日本語 & <orders>'}:n)}),fixture);
 await page.locator('#filter-options').evaluate(e=>e.open=true);
 await page.locator('#selector').fill('orders');await page.evaluate(()=>dbtShowFilter({expression:'orders',exclude:'',ids:['model'],diagnostics:[]}));
 await page.locator('#export-options').evaluate(e=>e.open=true);
 await page.locator('#export-svg').click();
 const svg=await page.evaluate(()=>atob(events.filter(e=>e.type==='export'&&e.id==='svg').at(-1).value.split(',')[1]));
 const decoded=Buffer.from(svg,'binary').toString('utf8');
 assert.match(decoded,/objednávky 日本語 &amp; &lt;orders&gt;/);assert.doesNotMatch(decoded,/unrelated/);assert.match(decoded,/downstream dependency/);
 assert.match(decoded,/#fafafa/);assert.doesNotMatch(decoded,/var\(--/);assert.doesNotMatch(decoded,/<script|https:|foreignObject/);
 await page.locator('#export-options').evaluate(e=>e.open=true);
 await page.locator('#export-legend').uncheck();await page.locator('#export-options').evaluate(e=>e.open=true);
 await page.locator('#export-svg').click();
 assert.doesNotMatch(await page.evaluate(()=>atob(events.filter(e=>e.type==='export'&&e.id==='svg').at(-1).value.split(',')[1])),/downstream dependency/);
 await page.locator('#export-options').evaluate(e=>e.open=true);
 await page.locator('#export-png').click();await page.waitForFunction(()=>events.some(e=>e.type==='export'&&e.id==='png'));
 const png=await page.evaluate(()=>events.filter(e=>e.type==='export'&&e.id==='png').at(-1).value);assert.ok(png.startsWith('data:image/png;base64,iVBOR'));
 await page.locator('#export-options').evaluate(e=>e.open=true);
 await page.locator('#copy-image').click();await page.waitForFunction(()=>events.some(e=>e.type==='export'&&e.id==='clipboard'));assert.ok(await page.evaluate(()=>events.filter(e=>e.type==='export'&&e.id==='clipboard').at(-1).value.startsWith('data:image/png;base64,')));
 await page.locator('#export-options').evaluate(e=>e.open=true);
 await page.locator('#export-legend').check();await page.locator('#filter-options').evaluate(e=>e.open=true);
 await page.locator('#clear-filter').click();await page.evaluate(p=>dbtUpdate(p),fixture);assert.deepEqual(errors,[]);
});
test('grouping preserves package and version collisions on representative large graphs',async()=>{
 const nodes=Array.from({length:180},(_,i)=>({id:'model:package'+(i%3)+':orders:v'+i,name:'orders',package:'package'+(i%3),kind:'model',schema:'schema'+(i%6),tags:['shared'],folder:'models/'+(i%10)}));
 const edges=nodes.slice(1).map((n,i)=>({source:nodes[i].id,target:n.id}));
 await page.evaluate(p=>dbtUpdate(p),{...fixture,focus:nodes[0].id,nodes,edges,boundary:[]});
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#group-by').selectOption('schema');assert.equal(await page.locator('.card:not(.boundary)').count(),180);
 assert.equal(await page.evaluate(()=>cy.nodes().filter(n=>n.data('group')).length),6);
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#group-by').selectOption('tag');assert.equal(await page.locator('.card:not(.boundary)').count(),180);
 await page.getByRole('button',{name:'▾ shared (180)',exact:true}).click();assert.equal(await page.locator('.card.collapsed').count(),1);
 await page.getByRole('button',{name:'▸ shared (180)',exact:true}).click();assert.equal(await page.locator('.card:not(.boundary)').count(),180);
 await page.locator('#view-options').evaluate(e=>e.open=true);
 await page.locator('#group-by').selectOption('');await page.evaluate(p=>dbtUpdate(p),fixture);assert.deepEqual(errors,[]);
});
test('execution badges expose provenance and route failed tests without relying on colors',async()=>{
 const execution={status:'failed',label:'Build failed',failingTests:2,details:'target: dev; invocation: run-1; run_results.json'};
 await page.evaluate(({fixture,execution})=>dbtUpdate({...fixture,nodes:fixture.nodes.map(n=>({...n,execution:n.id==='model'?execution:{status:'unknown',label:'Not run / result unavailable',failingTests:0,details:'No compatible result artifact'}}))}),{fixture,execution});
 const badge=page.getByRole('button',{name:/orders: 2 failing tests · Build failed/});assert.equal(await badge.count(),1);assert.match(await badge.getAttribute('aria-label'),/run_results.json/);
 await badge.click();assert.ok(await page.evaluate(()=>events.some(e=>e.type==='execution'&&e.id==='model')));
 await page.evaluate(d=>dbtShowDoc('model',d),doc);assert.match(await page.locator('#doc-content').innerText(),/test declarations/);

 await page.locator('#export-options').evaluate(e=>e.open=true);
 await page.locator('#export-svg').click();assert.match(await page.evaluate(()=>atob(events.filter(e=>e.type==='export'&&e.id==='svg').at(-1).value.split(',')[1])),/Build failed/);
 await page.evaluate(p=>dbtUpdate({...p,nodes:p.nodes.map(n=>({...n,execution:{status:'unknown',label:'Not run / result unavailable',failingTests:0,details:'Artifact no longer compatible'}}))}),fixture);
 assert.equal(await page.getByRole('button',{name:/Build failed/}).count(),0);
 assert.equal(await page.locator('.execution-badge[data-status=unknown]').count(),0);
 await page.evaluate(p=>dbtUpdate(p),fixture);
});
test('exports support every bundled edge style without invalid geometry',async()=>{
 for(const edgeStyle of ['bezier','taxi','round-taxi','segments','straight','unbundled-bezier','haystack']){
  await page.evaluate(p=>dbtUpdate(p),{...fixture,edgeStyle});
  const svg=await page.evaluate(()=>graphSvg());assert.doesNotMatch(svg,/NaN|undefined/);
 }
 assert.deepEqual(errors,[]);
});
test('column trace, declaration and provenance origins preserve owning identity and authored evidence',async()=>{
 await page.evaluate(({doc})=>{select('model',false);dbtShowDoc('model',{...doc,columns:[{...doc.columns[0],origins:[
  {resource:'model.package_a.orders.v2',column:'customer_id',direct:true,kind:'value',complete:true,expression:'o.customer_id',path:'/a/orders.sql',offset:12},
  {resource:'source.package_b.crm.customers',column:'id',direct:false,kind:'join',complete:false,expression:'o.customer_id = c.id',path:'/b/sources.yml',offset:34}
 ]}]})},{doc});
 await page.getByRole('button',{name:'Columns',exact:true}).click();
 await page.getByRole('button',{name:'id',exact:true}).click();
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='column'&&e.id==='model'&&e.value==='id')));
 await page.getByRole('button',{name:'Open declaration',exact:true}).click();
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='declaration'&&e.id==='model'&&e.value==='id')));
 const origins=page.getByRole('button',{name:/Open origin /});assert.equal(await origins.count(),2);
 assert.match(await origins.nth(0).innerText(),/package_a.orders.v2.customer_id.*direct passthrough/);
 assert.match(await origins.nth(1).innerText(),/join.*derived.*partial analysis.*o.customer_id = c.id/);
 await origins.nth(1).click();
 assert.deepEqual(await page.evaluate(()=>JSON.parse(events.filter(e=>e.type==='origin').at(-1).value)),{column:'id',index:1});
 assert.deepEqual(errors,[]);
});

test('test-only failure counts are visible and remain accurate across theme and evidence refresh',async()=>{
 const execution={status:'unknown',label:'Not run / result unavailable',failingTests:3,details:'prod · run-test-only · run_results.json'};
 const payload={...fixture,nodes:fixture.nodes.map(n=>n.id==='model'?{...n,execution}:n)};
 await page.evaluate(p=>dbtUpdate(p),payload);
 const badge=page.getByRole('button',{name:/orders: 3 failing tests/});
 assert.match(await badge.innerText(),/^3 failing tests/);
 assert.match(await badge.getAttribute('aria-label'),/prod.*run-test-only/);
 await badge.focus();await page.keyboard.press('Enter');
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='execution'&&e.id==='model')));
 for(const theme of [{bg:'#202124',fg:'#dfe1e5',panel:'#2b2d30'},{bg:'#fafafa',fg:'#222222',panel:'#eeeeee'}]){
  await page.evaluate(p=>dbtUpdate(p),{...payload,theme});
  assert.equal(await page.getByRole('button',{name:/3 failing tests/}).count(),1);
  assert.match(await page.evaluate(()=>graphSvg()),/3 failing tests/);
 }
 await page.evaluate(p=>dbtUpdate({...p,nodes:p.nodes.map(n=>n.execution?{...n,execution:{...n.execution,failingTests:0}}:n)}),payload);
 assert.equal(await page.getByRole('button',{name:/failing tests/}).count(),0);
 await page.evaluate(p=>dbtUpdate(p),fixture);assert.deepEqual(errors,[]);
});

test('Airflow task badges distinguish declared associations from dynamic project context',async()=>{
 const tasks=[{id:'mapping:3|finance|build|analytics',dag:'finance',task:'build',project:'analytics',projectRoot:'/project',selected:true,status:'Declared selection',selection:'fqn:analytics.orders.v2',exclude:'',path:'/project/.dbt-airflow.yml',line:3,url:'https://airflow.test/dags/finance/grid?task_id=build',ingestion:['landing.load'],problems:[]},
 {id:'mapping:4|dynamic|runtime|analytics',dag:'dynamic <script>bad()</script>',task:'runtime',project:'analytics',projectRoot:'/project',selected:false,status:'Unresolved selection — association unknown',selection:'{{ params.select }}',exclude:'',path:'/project/.dbt-airflow.yml',line:4,ingestion:[],problems:['Dynamic/runtime selection is unresolved.']}];
 await page.evaluate(p=>dbtUpdate(p),fixture);
 await page.evaluate(d=>dbtShowDoc('model',d),{...doc,airflow:{tasks,selectedCount:1,unresolvedCount:1,truncated:false}});
 const badge=page.getByRole('button',{name:'Airflow finance / build',exact:true});
 assert.equal(await badge.count(),1);
 assert.equal(await page.getByRole('button',{name:'Airflow selection unknown (1)',exact:true}).count(),1);
 await badge.focus();await page.keyboard.press('Enter');
 assert.equal(await page.getByRole('button',{name:'Metadata',exact:true}).getAttribute('class'),'active');
 assert.match(await page.locator('#doc-content').innerText(),/Project: analytics \(\/project\)/);
 assert.match(await page.locator('#doc-content').innerText(),/Unresolved selection — association unknown/);
 assert.match(await page.locator('#doc-content').innerText(),/Mapping: \/project\/\.dbt-airflow.yml:3/);
 assert.match(await page.locator('#doc-content').innerText(),/Declared upstream ingestion: landing.load/);
 assert.equal(await page.locator('#doc-content script').count(),0);
 await page.getByRole('button',{name:'Open mapping definition for finance / build',exact:true}).click();
 let message=await page.evaluate(()=>events.filter(e=>e.type==='airflow').at(-1));
 assert.equal(message.id,'model');assert.deepEqual(JSON.parse(message.value),{mapping:tasks[0].id,target:'definition'});
 await page.getByRole('button',{name:'Open Airflow task for finance / build',exact:true}).click();
 message=await page.evaluate(()=>events.filter(e=>e.type==='airflow').at(-1));
 assert.deepEqual(JSON.parse(message.value),{mapping:tasks[0].id,target:'browser'});
 assert.equal(await page.getByRole('button',{name:'Open Airflow task for '+tasks[1].dag+' / runtime',exact:true}).isDisabled(),true);
 await page.evaluate(d=>dbtShowDoc('model',d),{...doc,airflow:{tasks:[],selectedCount:0,unresolvedCount:0,truncated:false}});
 assert.equal(await page.locator('.airflow-badge').count(),0);
 assert.match(await page.locator('#doc-content').innerText(),/No declared Airflow tasks select this resource/);
 await page.evaluate(()=>select('other',false));
 await page.evaluate(d=>dbtShowDoc('model',d),{...doc,airflow:{tasks,selectedCount:1,unresolvedCount:1}});
 assert.equal(await page.locator('.airflow-badge').count(),0);
 await page.evaluate(()=>select('model',false));await page.evaluate(d=>dbtShowDoc('model',d),doc);
 await page.getByRole('button',{name:'Columns',exact:true}).click();
 assert.deepEqual(errors,[]);
});

test('Airflow badges and detail entries stay bounded and report hidden mappings',async()=>{
 const tasks=Array.from({length:50},(_,i)=>({id:'mapping:'+i,dag:'daily',task:'build_'+i,project:'analytics',projectRoot:'/project',selected:true,status:'Declared selection',selection:'orders',path:'/project/.dbt-airflow.yml',line:i+2,ingestion:[],problems:[]}));
 await page.evaluate(d=>dbtShowDoc('model',d),{...doc,airflow:{tasks,selectedCount:52,unresolvedCount:0,truncated:true}});
 assert.equal(await page.locator('.airflow-badge').count(),4);
 await page.getByRole('button',{name:'49 more Airflow tasks',exact:true}).click();
 assert.equal(await page.locator('.airflow-task').count(),50);
 assert.match(await page.locator('#doc-content').innerText(),/Showing the first 50 task mappings/);
 await page.evaluate(d=>dbtShowDoc('model',d),doc);await page.getByRole('button',{name:'Columns',exact:true}).click();
});

test('Runner selection explicitly updates Lineage fields while stale local replies are rejected',async()=>{
 await page.evaluate(p=>dbtUpdate(p),fixture);
 await page.locator('#filter-options').evaluate(e=>e.open=true);
 await page.locator('#selector').fill('old');await page.locator('#exclude').fill('tag:old');
 await page.evaluate(()=>dbtShowFilter({fromRunner:true,expression:'fqn:analytics.orders',exclude:'',ids:['model'],diagnostics:[]}));
 assert.equal(await page.locator('#selector').inputValue(),'fqn:analytics.orders');
 assert.equal(await page.locator('#exclude').inputValue(),'');
 assert.match(await page.locator('#filter-status').innerText(),/1 matches/);
 await page.evaluate(()=>dbtShowFilter({expression:'old',exclude:'tag:old',ids:['other'],diagnostics:[]}));
 assert.equal(await page.locator('#selector').inputValue(),'fqn:analytics.orders');
 assert.match(await page.locator('#filter-status').innerText(),/1 matches/);
 await page.locator('#filter-options').evaluate(e=>e.open=true);await page.getByRole('button',{name:'Clear filter',exact:true}).click();
});

test('Lineage filters remain independent and only Use in Runner requests a handoff',async()=>{
 await page.locator('#filter-options').evaluate(e=>e.open=true);
 await page.locator('#selector').fill('tag:finance+');await page.locator('#exclude').fill('');
 await page.locator('#filter-form').evaluate(form=>form.requestSubmit());
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='filter').at(-1).shared),undefined);
 await page.locator('#use-runner').click();assert.equal(await page.evaluate(()=>events.at(-1).type),'use-runner-selection');
 await page.evaluate(()=>dbtShowFilter({expression:'tag:finance+',exclude:'',ids:['model'],diagnostics:[]}));
 await page.evaluate(p=>dbtUpdate(p),fixture);
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='filter').at(-1).shared),undefined);
 await page.locator('#filter-options').evaluate(e=>e.open=true);await page.getByRole('button',{name:'Clear filter',exact:true}).click();
 assert.deepEqual(await page.evaluate(()=>events.filter(e=>e.type==='filter').at(-1)),{type:'filter',id:'',value:''});
});


test('secondary controls stay grouped and usable in a narrow window',async()=>{
 try{
  await page.setViewportSize({width:600,height:600});
  for(const id of ['filter','view','export']){
   await page.locator('#'+id+'-options').evaluate(e=>e.open=false);
   const summary=page.locator('#'+id+'-options summary');
   await summary.click();
   const popover=await page.locator('#'+id+'-options .control-popover').boundingBox();
   assert.ok(popover.x>=0&&popover.x+popover.width<=600);
   await page.keyboard.press('Escape');
   assert.equal(await page.locator('#'+id+'-options .control-popover').isVisible(),false);
  }
  const graph=await page.locator('#main').boundingBox();
  assert.ok(graph.height>430,'The graph retains usable space with context and controls');
  assert.equal(await page.locator('#filter-form').isVisible(),false);
  assert.equal(await page.locator('#export-png').isVisible(),false);
 }finally{await page.setViewportSize({width:1400,height:850})}
});

test('resource cards hide unresolved configuration and unknown result badges, with keyboard inspection',async()=>{
 const payload={...fixture,nodes:fixture.nodes.map(n=>n.id==='model'?{...n,materialized:"{{ config.get('materialized') }}",schema:'null',execution:{status:'unknown',label:'Not run / result unavailable',failingTests:0,details:'Missing artifact'}}:n)};
 await page.evaluate(p=>dbtUpdate(p),payload);
 const card=page.locator('.card[data-kind=model]').filter({hasText:'orders'});
 assert.doesNotMatch(await card.innerText(),/\{\{|null|Not run/);
 assert.equal(await page.locator('.execution-badge').count(),0);
 assert.match(await card.getAttribute('aria-label'),/graph focus/);
 await page.locator('#resource-picker').selectOption('model');
 assert.ok(await page.evaluate(()=>events.some(e=>e.type==='select'&&e.id==='model')));
 await page.evaluate(d=>dbtShowDoc('model',{...d,shortName:'orders',identity:'model.analytics.orders'}),doc);
 assert.equal(await page.locator('#doc-name').innerText(),'orders');
 await page.locator('#copy-identity').click();
 assert.deepEqual(await page.evaluate(()=>events.at(-1)),{type:'copy',id:'',value:'model.analytics.orders'});
 await card.focus();await page.keyboard.press('Enter');
 assert.equal(await page.locator('.card.selected').count(),1);
 assert.match(await page.locator('#selection-context').innerText(),/Focus: orders · Selected: orders/);
 await page.evaluate(p=>dbtUpdate(p),fixture);
});

test('inspector and long resource names remain readable at narrow and wide sizes',async()=>{
 try{
  for(const width of [600,1400]){
   await page.setViewportSize({width,height:800});
   await page.evaluate(p=>dbtUpdate(p),fixture);
   await page.evaluate(d=>{select('model',false);dbtShowDoc('model',{...d,shortName:'orders',identity:'model.analytics.orders',columns:[{name:'customer_identifier_with_a_long_name',type:'decimal(38, 8)',description:'Customer identity from the upstream source',declarations:['unique','not_null']}]})},doc);
   await page.getByRole('button',{name:'Columns',exact:true}).click();
   const main=await page.locator('#main').boundingBox(),inspector=await page.locator('#docs').boundingBox();
   assert.ok(main.width<=width);assert.ok(inspector.width>=240);assert.ok(inspector.x+inspector.width<=width);
   await page.locator('#handle').focus();const before=inspector.width;await page.keyboard.press('ArrowRight');
   assert.ok((await page.locator('#docs').boundingBox()).width<before);
   const column=page.getByRole('button',{name:'customer_identifier_with_a_long_name',exact:true});
   await column.click();await page.getByRole('button',{name:'Open declaration',exact:true}).click();
   assert.deepEqual(await page.evaluate(()=>events.at(-1)),{type:'declaration',id:'model',value:'customer_identifier_with_a_long_name'});
   if(process.env.DBT_UX_SCREENSHOT_DIR)await page.screenshot({path:path.join(process.env.DBT_UX_SCREENSHOT_DIR,'lineage-'+width+'.png')});
  }
 }finally{await page.setViewportSize({width:1400,height:850});await page.evaluate(p=>dbtUpdate(p),fixture)}
 assert.deepEqual(errors,[]);
});
