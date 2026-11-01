// Run with NODE_PATH pointing to an installed Playwright package and DBT_CHROMIUM set when needed.
const {test, after, before} = require('node:test');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.DBT_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/lineage');
let browser, page, errors=[];
const fixture = {canGenerateDocs:true,focus:'model',nodes:[{id:'source',name:'events',kind:'source_table',package:'analytics',schema:'raw'},{id:'model',name:'orders',kind:'model',package:'analytics',schema:'mart',focus:true,materialized:'incremental'},{id:'report',name:'report',kind:'model',package:'analytics'},{id:'other',name:'unrelated',kind:'seed',package:'analytics'}],edges:[{source:'source',target:'model'},{source:'model',target:'report'}],boundary:[{direction:'upstream',count:7,anchors:['source']}],direction:'LR'};
async function createLineagePage(viewport={width:1400,height:850}){
 const view=await browser.newPage({viewport});view.on('pageerror',e=>errors.push(e.message));
 const scripts=['cytoscape.min.js','dagre.min.js','cytoscape-dagre.min.js'].map(x=>'<script>'+fs.readFileSync(path.join(root,'vendor',x),'utf8').replaceAll('</script','<\\/script')+'</script>').join('');
 const html=fs.readFileSync(path.join(root,'index.html'),'utf8').replace('<!-- SCRIPTS -->',()=>scripts+'<script>window.events=[];window.dbtSend=x=>window.events.push(JSON.parse(x));</script>');
 await view.setContent(html);await view.evaluate(p=>window.dbtUpdate(p),fixture);
 return view;
}
before(async()=>{
 browser=await chromium.launch({headless:true,executablePath:process.env.DBT_CHROMIUM, args:['--no-sandbox']});
 page=await createLineagePage();
});
after(async()=>{await browser.close()});
const update=payload=>page.evaluate(p=>dbtUpdate(p),payload);
async function openExport(){if(!await page.locator('#export-menu').evaluate(e=>e.open))await page.locator('#export-menu summary').click()}
async function clickExport(id){await openExport();await page.locator('#'+id).click()}
const lastExport=id=>page.evaluate(id=>events.filter(e=>e.type==='export'&&e.id===id).at(-1).value,id);
const exportedSvg=async()=>Buffer.from((await lastExport('svg')).split(',')[1],'base64').toString('latin1');
const hasEvent=(type,id)=>page.evaluate(([type,id])=>events.some(e=>e.type===type&&e.id===id),[type,id]);
async function nodePosition(id){return page.evaluate(id=>{const p=cy.getElementById(id).renderedPosition(),r=document.getElementById('cy').getBoundingClientRect();return{x:r.x+p.x,y:r.y+p.y}},id)}
test('bundled graph loads and cards show resource identity',async()=>{assert.deepEqual(errors,[]);assert.equal(await page.locator('.card:not(.boundary)').count(),4);assert.match(await page.locator('#cards').innerText(),/orders/)});
test('documentation closes by keyboard and returns focus to its resource',async()=>{
 const view=await createLineagePage();
 try{
  const card=view.getByRole('button',{name:'orders · model · mart · analytics · graph focus',exact:true});
  for(const close of ['Escape','Enter']){
   await card.focus();await view.keyboard.press('Enter');
   await view.evaluate(()=>{cy.zoom(.4);cy.center(cy.getElementById('model'))});
   await view.locator('#close-docs').focus();await view.keyboard.press(close);
   assert.equal(await view.locator('#docs').isVisible(),false);
   assert.equal(await card.evaluate(e=>e===document.activeElement),true);
   assert.equal(await view.evaluate(()=>cy.zoom()),.4);
  }
  await view.keyboard.press('Enter');await view.locator('#docs-divider').focus();
  for(const name of ['aria-valuemin','aria-valuemax','aria-valuenow'])assert.ok(Number(await view.locator('#docs-divider').getAttribute(name))>0);
  await view.keyboard.press('Escape');assert.equal(await card.evaluate(e=>e===document.activeElement),true);
  await view.setViewportSize({width:480,height:700});
  await view.locator('#group-by').selectOption('schema');
  await view.locator('.card[aria-label^="orders"]').focus();await view.keyboard.press('Enter');
  const divider=view.locator('#docs-divider'),value=Number(await divider.getAttribute('aria-valuenow'));
  assert.ok(value>=Number(await divider.getAttribute('aria-valuemin'))&&value<=Number(await divider.getAttribute('aria-valuemax')));
 }finally{await view.close()}
});
test('export returns keyboard focus and Escape dismisses tooltips',async()=>{
 const view=await createLineagePage();
 try{
  await view.locator('#export-menu summary').click();
  await view.locator('#export-svg').focus();await view.keyboard.press('Enter');
  assert.equal(await view.locator('#export-menu summary').evaluate(e=>e===document.activeElement),true);
  assert.equal(await view.locator('#export-menu').evaluate(e=>e.open),false);
  await view.locator('#zoom-in').focus();assert.equal(await view.getByRole('tooltip').isVisible(),true);
  await view.keyboard.press('Escape');assert.equal(await view.getByRole('tooltip').isVisible(),false);
  assert.equal(await view.locator('#zoom-in').evaluate(e=>e===document.activeElement),true);
 }finally{await view.close()}
});
test('empty graphs disable unavailable actions and recover when resources load',async()=>{
 const view=await createLineagePage();
 try{
  await view.locator('.card[aria-label^="orders"]').focus();
  await view.evaluate(()=>dbtUpdate({nodes:[],edges:[],boundary:[],empty:'No dbt resources found.'}));
  assert.equal(await view.locator('#search').evaluate(e=>e===document.activeElement),true);
  assert.equal(await view.locator('#empty').innerText(),'No dbt resources found.');
  for(const id of ['fit','zoom-in','zoom-out','show-docs','export-svg','resource-picker'])assert.equal(await view.locator('#'+id).isDisabled(),true);
  assert.equal(await view.locator('#clear-filter').isEnabled(),true);
  await view.evaluate(p=>dbtUpdate(p),fixture);
  for(const id of ['fit','zoom-in','zoom-out','show-docs','export-svg','resource-picker'])assert.equal(await view.locator('#'+id).isEnabled(),true);
  await view.locator('#selector').fill('missing');
  await view.evaluate(()=>dbtShowFilter({expression:'missing',exclude:'',ids:[],diagnostics:[]}));
  assert.equal(await view.locator('#fit').isDisabled(),true);
  assert.equal(await view.locator('#group-by').isEnabled(),true);
  await view.locator('#clear-filter').click();
  assert.equal(await view.locator('#fit').isEnabled(),true);
 }finally{await view.close()}
});
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
test('resource and evidence keyboard focus survive refresh without moving the viewport',async()=>{
 const view=await createLineagePage();
 try{
  const payload={...fixture,nodes:fixture.nodes.map(n=>n.id==='model'?{...n,execution:{status:'passed',label:'Build passed',details:'dev',failingTests:0}}:n)};
  await view.evaluate(p=>dbtUpdate(p),payload);
  for(const selector of ['.card[data-kind="model"][aria-label^="orders"]','.execution-badge']){
   await view.locator(selector).focus();
   const viewport=await view.evaluate(()=>({zoom:cy.zoom(),pan:cy.pan()}));
   await view.evaluate(p=>dbtUpdate(p),payload);
   assert.equal(await view.locator(selector).evaluate(e=>e===document.activeElement),true);
   assert.deepEqual(await view.evaluate(()=>({zoom:cy.zoom(),pan:cy.pan()})),viewport);
  }
  await view.evaluate(p=>dbtUpdate({...p,nodes:p.nodes.filter(n=>n.id!=='model'),edges:[]}),payload);
  assert.equal(await view.locator('#resource-picker').evaluate(e=>e===document.activeElement),true);
 }finally{await view.close()}
});
test('boundary expansion is available through Enter and Space',async()=>{
 const view=await createLineagePage();
 try{
  const boundary=view.getByRole('button',{name:'+ 7 more · expand upstream dependencies',exact:true});
  await boundary.focus();
  for(const key of ['Enter','Space'])await view.keyboard.press(key);
  assert.equal(await view.evaluate(()=>events.filter(e=>e.type==='expand'&&e.id==='upstream').length),2);
  assert.equal(await view.evaluate(()=>events.filter(e=>e.type==='focus').length),0);
 }finally{await view.close()}
});
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

 await clickExport('export-svg');
 const svg=await exportedSvg();
 const decoded=Buffer.from(svg,'binary').toString('utf8');
 assert.match(decoded,/objednávky 日本語 &amp; &lt;orders&gt;/);assert.doesNotMatch(decoded,/unrelated/);assert.match(decoded,/downstream dependency/);
 assert.match(decoded,/#fafafa/);assert.doesNotMatch(decoded,/var\(--/);assert.doesNotMatch(decoded,/<script|https:|foreignObject/);

 await openExport();await page.locator('#export-legend').uncheck();
 await clickExport('export-svg');
 assert.doesNotMatch(await exportedSvg(),/downstream dependency/);

 await clickExport('export-png');await page.waitForFunction(()=>events.some(e=>e.type==='export'&&e.id==='png'));
 const png=await lastExport('png');assert.ok(png.startsWith('data:image/png;base64,iVBOR'));

 await clickExport('copy-image');await page.waitForFunction(()=>events.some(e=>e.type==='export'&&e.id==='clipboard'));assert.ok((await lastExport('clipboard')).startsWith('data:image/png;base64,'));

 await openExport();await page.locator('#export-legend').check();await page.locator('#export-menu').evaluate(e=>e.open=false);
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


 await clickExport('export-svg');assert.match(await exportedSvg(),/Build failed/);
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


test('everyday lineage controls remain visible and export options fit at narrow widths',async()=>{
 try{
  for(const width of [600,1400]){
   await page.setViewportSize({width,height:850});
   assert.equal(await page.locator('#graph-controls details').count(),0);
   for(const id of ['selector','exclude','group-by','show-minimap','resource-picker'])
    assert.equal(await page.locator('#'+id).isVisible(),true);
   for(const name of ['Apply filter','Clear filter','Use in Run Configuration']){
    const button=page.getByRole('button',{name,exact:true});
    assert.equal(await button.isVisible(),true);
    const box=await button.boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width);
    assert.equal(await button.evaluate(e=>e.closest('details,[popover]')),null);
   }
   await page.locator('#export-menu').evaluate(e=>e.open=false);
   assert.equal(await page.locator('#export-svg').isVisible(),false);
   await page.locator('#export-menu summary').click();
   for(const id of ['export-png','export-svg','copy-image','export-legend']){
    assert.equal(await page.locator('#'+id).isVisible(),true);
    const box=await page.locator('#'+id).boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width);
   }
   await page.locator('#export-menu summary').focus();await page.keyboard.press('Escape');
   assert.equal(await page.locator('#export-svg').isVisible(),false);
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

test('open documentation refreshes in place while closed documentation stays closed',async()=>{
 await update(fixture);
 await page.evaluate(()=>{events=[];select('model',false)});
 await page.getByRole('button',{name:'Open Documentation',exact:true}).click();
 assert.deepEqual(await page.evaluate(()=>events.at(-1)),{type:'select',id:'model',value:''});
 await page.evaluate(()=>events=[]);
 await update(fixture);
 assert.equal(await page.evaluate(()=>events.filter(e=>e.type==='select').length),1);
 assert.equal(await page.locator('#docs').isVisible(),true);
 await page.locator('#close-docs').click();await update(fixture);
 assert.equal(await page.locator('#docs').isVisible(),false);
 assert.deepEqual(errors,[]);
});


test('documentation test links open source without changing the selected resource',async()=>{
 await update(fixture);await page.evaluate(()=>select('model'));
 const source='/project with spaces/models/tests.yml:42';
 await page.evaluate(source=>dbtShowDocumentation({id:'model',column:'',html:'<h3>Tests (1)</h3><p><a href="dbt-test:'+encodeURIComponent(source)+'">ab_test</a></p>'}),source);
 await page.getByRole('link',{name:'ab_test',exact:true}).click();
 assert.deepEqual(await page.evaluate(()=>events.at(-1)),{type:'test-source',id:'model',value:source});
 assert.equal(await page.evaluate(()=>selected),'model');
 assert.match(await page.locator('#docs-content').innerText(),/Tests \(1\)/);
 await page.locator('#close-docs').click();
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
 await openExport();
 for(const [id,text]of [['fit','Fit graph'],['apply-filter','Apply'],['clear-filter','Clear'],['use-config','Run configuration…'],['export-png','PNG'],['export-svg','SVG']])assert.equal(await page.locator('#'+id).innerText(),text);
 assert.equal(await page.locator('#zoom-in img').count(),1);
 await page.locator('#export-menu').evaluate(e=>e.open=false);
 await page.locator('#resource-picker').selectOption('model');
 await page.mouse.click(10,820);
 assert.equal(await page.locator('#resource-picker').inputValue(),'');
 assert.doesNotMatch(await page.locator('#selection-context').innerText(),/Selected:/);
 assert.deepEqual(errors,[]);
});


test('search highlighting survives graph refresh without moving the viewport',async()=>{
 await page.evaluate(()=>closeDocumentation());await update(fixture);
 await page.locator('#search').fill('orders');
 const before=await page.evaluate(()=>({zoom:cy.zoom(),pan:cy.pan()}));
 await update({...fixture,status:'Source changed'});
 assert.equal(await page.locator('#search').inputValue(),'orders');
 assert.equal(await page.locator('.card:not(.dim):not(.boundary)').count(),1);
 assert.equal(await page.locator('.card:not(.dim):not(.boundary) b').innerText(),'orders');
 assert.deepEqual(await page.evaluate(()=>({zoom:cy.zoom(),pan:cy.pan()})),before);
 await page.locator('#clear-search').click();
});
test('execution badge Enter and Space each open results without selecting documentation',async()=>{
 const execution={status:'failed',label:'Build failed',failingTests:1,details:'dev · invocation-1'};
 await update({...fixture,nodes:fixture.nodes.map(n=>n.id==='model'?{...n,execution}:n)});
 const badge=page.locator('.execution-badge');
 for(const key of ['Enter','Space']){
  await page.evaluate(()=>events=[]);await badge.focus();await page.keyboard.press(key);
  assert.deepEqual(await page.evaluate(()=>events.map(e=>e.type)),['execution']);
 }
 await update(fixture);
});
test('documentation Markdown links request browser navigation',async()=>{
 await page.evaluate(()=>select('model'));
 await page.evaluate(()=>dbtShowDocumentation({id:'model',column:'',html:'<p><a href="https://example.com/docs">Guide</a></p>'}));
 await page.getByRole('link',{name:'Guide',exact:true}).click();
 assert.deepEqual(await page.evaluate(()=>events.at(-1)),{type:'external-link',id:'',value:'https://example.com/docs'});
 await page.locator('#close-docs').click();
});


test('documentation refresh preserves scroll while explicit resource navigation resets it',async()=>{
 await update(fixture);await page.evaluate(()=>select('model'));
 const html='<h3>Orders</h3>'+Array.from({length:70},(_,i)=>'<p>Column '+i+' description</p>').join('');
 await page.evaluate(html=>dbtShowDocumentation({id:'model',column:'',html}),html);
 await page.locator('#docs').evaluate(e=>e.scrollTop=400);
 const scroll=await page.locator('#docs').evaluate(e=>e.scrollTop);assert.ok(scroll>0);
 await update({...fixture,status:'Metadata refreshed'});
 await page.evaluate(html=>dbtShowDocumentation({id:'model',column:'',html}),html);
 assert.equal(await page.locator('#docs').evaluate(e=>e.scrollTop),scroll);
 await page.evaluate(()=>select('source'));
 await page.evaluate(html=>dbtShowDocumentation({id:'source',column:'',html}),html);
 assert.equal(await page.locator('#docs').evaluate(e=>e.scrollTop),0);
 await page.evaluate(()=>closeDocumentation());
});

test('opening documentation keeps the clicked card visible at a readable zoom in narrow tool windows',async()=>{
 for(const width of [600,640])for(const direction of ['LR','TB']){
  const view=await createLineagePage({width,height:650});
  try{
   await view.evaluate(p=>dbtUpdate(p),{...fixture,direction});
   const before=await view.evaluate(()=>({point:cy.getElementById('model').renderedPosition(),zoom:cy.zoom()}));
   const pane=await view.locator('#cy').boundingBox();
   await view.mouse.click(pane.x+before.point.x,pane.y+before.point.y);
   await view.locator('#docs').waitFor({state:'visible'});
   const card=await view.locator('.card.selected').boundingBox(),graph=await view.locator('#graph-pane').boundingBox();
   assert.ok(card.x>=graph.x&&card.x+card.width<=graph.x+graph.width,'The selected resource remains entirely visible beside its documentation');
   assert.ok(card.y>=graph.y&&card.y+card.height<=graph.y+graph.height);
   const zoom=await view.evaluate(()=>cy.zoom());
   assert.ok(zoom>=.8&&zoom<=before.zoom,'Opening documentation retains a readable zoom');
   if(direction==='LR')assert.equal(zoom,before.zoom,'Keep the zoom when the resource already fits');
  }finally{await view.close()}
 }
});

test('documentation links remain legible in light and dark host themes',async()=>{
 const view=await createLineagePage();
 try{
  for(const theme of [{bg:'#202124',fg:'#dfe1e5',panel:'#2b2d30'},{bg:'#fafafa',fg:'#222222',panel:'#eeeeee'}]){
   await view.evaluate(p=>dbtUpdate(p),{...fixture,theme});
   await view.evaluate(()=>{select('model');dbtShowDocumentation({id:'model',column:'',html:'<a href="dbt-column:order_id">order_id</a>'})});
   const contrast=await view.evaluate(()=>{
    const canvas=document.createElement('canvas');canvas.width=canvas.height=1;const context=canvas.getContext('2d');
    function luminance(color){context.fillStyle=color;context.fillRect(0,0,1,1);const rgb=[...context.getImageData(0,0,1,1).data].slice(0,3).map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722}
    const a=luminance(getComputedStyle(document.querySelector('#docs a')).color),b=luminance(getComputedStyle(document.querySelector('#docs')).backgroundColor);
    return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
   });
   assert.ok(contrast>=4.5,`Documentation text contrast is ${contrast.toFixed(2)}:1 for ${theme.panel}`);
  }
 }finally{await view.close()}
});

test('clipped model titles expose different suffixes while keeping full accessible identities',async()=>{
 const view=await createLineagePage();
 try{
  const prefix='customer_orders_daily_revenue_by_marketing_campaign_and_region_';
  const nodes=['current','previous'].map(suffix=>({id:suffix,name:prefix+suffix,kind:'model',package:'analytics',schema:'mart'}));
  await view.evaluate(p=>dbtUpdate(p),{...fixture,focus:'current',nodes,edges:[],boundary:[]});
  for(const node of nodes){
   const card=view.getByRole('button',{name:new RegExp(node.name)});
   assert.match(await card.getAttribute('aria-label'),/mart · analytics/);
   assert.equal(await card.locator('small').last().innerText(),'mart · analytics');
   const tail=card.locator('.name-end');assert.ok((await tail.innerText()).endsWith(node.id));
   const visible=await tail.evaluate((element,suffix)=>{
    const range=document.createRange(),text=element.firstChild;range.setStart(text,text.length-suffix.length);range.setEnd(text,text.length);
    const word=range.getBoundingClientRect(),title=element.closest('b').getBoundingClientRect();
    return word.x>=title.x&&word.right<=title.right+1&&word.y>=title.y&&word.bottom<=title.bottom+1;
   },node.id);
   assert.ok(visible,`The distinguishing ${node.id} suffix is visible`);
  }
  assert.equal(await view.locator('.card[title]').count(),0,'Resource identity does not require a hover tooltip');
 }finally{await view.close()}
});

test('search reports zero, one and multiple matches and clears its own feedback',async()=>{
 const view=await createLineagePage();
 try{
  await view.locator('#search').fill('zzzz_no_match');
  assert.equal(await view.locator('#search-status').innerText(),'No resources match. Clear search to restore the graph.');
  assert.equal(await view.locator('#search-status').isVisible(),true);
  await view.locator('#search').fill('orders');
  assert.equal(await view.locator('#search-status').innerText(),'1 matching resource');
  await view.locator('#search').fill('analytics');
  assert.equal(await view.locator('#search-status').innerText(),'4 matching resources');
  await view.getByRole('button',{name:'Clear search',exact:true}).click();
  assert.equal(await view.locator('#search-status').innerText(),'');
  assert.equal(await view.locator('.card:not(.dim):not(.boundary)').count(),3,'Clearing search restores the selected resource and its dependencies');
 }finally{await view.close()}
});

test('lineage guidance stays discoverable after loading and selection, with keyboard disclosure',async()=>{
 const view=await createLineagePage();
 try{
  const help=view.locator('#lineage-help'),summary=help.locator('summary');
  assert.equal(await summary.innerText(),'Lineage help');assert.equal(await help.evaluate(e=>e.open),false);
  await view.evaluate(()=>select('model'));
  await summary.focus();await view.keyboard.press('Enter');
  assert.equal(await help.evaluate(e=>e.open),true);
  assert.match(await help.innerText(),/Shift\+Enter/);
  assert.match(await help.innerText(),/tag:finance\+/);
  assert.match(await help.innerText(),/five more dependency levels/);
  await view.keyboard.press('Enter');assert.equal(await help.evaluate(e=>e.open),false);
 }finally{await view.close()}
});
